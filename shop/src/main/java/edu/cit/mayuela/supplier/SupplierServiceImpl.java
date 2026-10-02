package edu.cit.mayuela.supplier;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The anti-corruption layer behind {@link SupplierGateway}.
 *
 * Owns the full translation between this application's vocabulary (product
 * ids, units) and LegacySupply's (SupplierSku, cases, numeric status codes).
 * Other modules only ever see the gateway's interface and its result types.
 *
 * Duplicate protection for one product comes from three layers:
 *  1. an open reorder is reused instead of a new one being created,
 *  2. all creation is serialised on a lock inside this singleton,
 *  3. a partial unique index on supplier_orders backs the first two up.
 * A resend always carries the stored X-Request-Id, and always looks the
 * BuyerRef up at LegacySupply before posting, so a retry can never produce a
 * second purchase order.
 */
@Service
class SupplierServiceImpl implements SupplierGateway {

    private static final List<ReorderStatus> OPEN_STATUSES =
            List.of(ReorderStatus.PENDING, ReorderStatus.ACCEPTED,
                    ReorderStatus.PICKING, ReorderStatus.SHIPPED);

    private final SupplierOrderRepository repository;
    private final SupplierCatalog catalog;
    private final LegacySupplyClient client;
    private final ApplicationEventPublisher eventPublisher;
    private final org.slf4j.Logger log = SupplierLogger.get();

    /** Serialises create-then-check for a product (single JVM). */
    private final Object reorderLock = new Object();

    /**
     * First, immediate send attempts run off the caller's thread so a slow or
     * unavailable supplier never blocks an order being placed. The scheduled
     * retry job remains responsible for anything still pending.
     */
    private final ExecutorService firstAttemptExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "supplier-first-attempt");
        t.setDaemon(true);
        return t;
    });

    SupplierServiceImpl(SupplierOrderRepository repository,
                        SupplierCatalog catalog,
                        LegacySupplyClient client,
                        ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.catalog = catalog;
        this.client = client;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Creates (or reuses) the open reorder for a product and hands the send to a
     * background thread.
     *
     * Owns its own transaction rather than relying on the caller, because not
     * every caller has one: the shop deliberately triggers reorders from an
     * after-commit callback so no HTTP happens inside its order transaction, and
     * that callback runs with the transaction already finished. Without this the
     * {@code saveAndFlush} below fails with "No active transaction" and the
     * shortage is never reordered. When a transaction does exist (a backorder
     * being settled) this simply joins it, and no HTTP is added to either case
     * because the send itself is dispatched to {@link #firstAttemptExecutor}.
     */
    @Override
    @Transactional
    public ReorderResult placeReorder(String productId, int units) {
        SupplierOrder order;
        synchronized (reorderLock) {
            order = openOrderFor(productId).orElse(null);
            if (order == null) {
                order = createPendingOrder(productId, units);
            }
        }
        if (order == null) {
            log.warn("No supplier mapping for product " + productId + "; reorder not recorded");
            return new ReorderResult(null, productId, ReorderStatus.FAILED, units, 0,
                    "", "", "", "No supplier mapping for product " + productId);
        }
        if (order.getStatus() == ReorderStatus.PENDING) {
            submitFirstAttempt(order);
        }
        return toResult(order);
    }

    @Override
    public boolean hasOpenReorder(String productId) {
        return openOrderFor(productId).isPresent();
    }

    @Override
    public List<ReorderResult> listReorders() {
        return repository.findAllByOrderByCreatedAtAsc().stream().map(this::toResult).toList();
    }

    private Optional<SupplierOrder> openOrderFor(String productId) {
        return repository.findFirstByProductIdAndStatusInOrderByCreatedAtAsc(productId, OPEN_STATUSES);
    }

    private SupplierOrder createPendingOrder(String productId, int units) {
        if (catalog.find(productId) == null) {
            return null;
        }
        int cases = catalog.casesFor(productId, units);
        String reference = "RO-" + java.util.UUID.randomUUID();
        SupplierOrder order = new SupplierOrder();
        order.setProductId(productId);
        order.setCases(cases);
        order.setUnits(units);
        order.setStatus(ReorderStatus.PENDING);
        order.setBuyerRef(reference);
        order.setRequestId(reference);
        try {
            repository.saveAndFlush(order);
        } catch (DataIntegrityViolationException e) {
            // another thread won the race; reuse the row it created
            return openOrderFor(productId).orElse(null);
        }
        return order;
    }

    /**
     * Hands the first send to a background thread.
     *
     * Only the id crosses the thread boundary. Passing the entity itself would
     * hand the worker an instance still attached to <em>this</em> thread's
     * persistence context, so its {@code save} becomes a merge of a detached
     * instance that races the status poll job on the same row - surfacing as
     * StaleObjectStateException or "No active transaction" and leaving the
     * purchase order stuck in PENDING with nobody left to send it. Re-reading
     * the row on the worker gives it its own session and a version that is
     * genuinely current.
     */
    private void submitFirstAttempt(SupplierOrder order) {
        Long id = order.getId();
        try {
            firstAttemptExecutor.execute(() -> {
                try {
                    repository.findById(id).ifPresent(this::attemptSend);
                } catch (RuntimeException e) {
                    log.error("First send attempt failed for supplier order " + id, e);
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("Send executor rejected supplier order " + id + "; retry job will send it");
        }
    }

    /**
     * Sends one pending reorder. Safe to call repeatedly: on a resend the
     * BuyerRef is looked up first so an order LegacySupply already has is
     * adopted instead of created again.
     *
     * @return true when the order now has a supplier acknowledgement
     */
    boolean attemptSend(SupplierOrder order) {
        SupplierItem item = catalog.find(order.getProductId());
        if (item == null) {
            fail(order, "No supplier mapping for product " + order.getProductId());
            return false;
        }
        boolean resend = order.getAttempts() > 0;
        order.setAttempts(order.getAttempts() + 1);
        repository.save(order);
        try {
            if (resend) {
                List<LegacyXml.OrderStatus> existing = client.findOrdersByBuyerRef(order.getBuyerRef());
                if (!existing.isEmpty()) {
                    adopt(order, existing.get(0));
                    log.info("Adopted existing supplier order " + order.getPoNumber()
                            + " for " + order.getBuyerRef());
                    return true;
                }
            }
            LegacyXml.Ack ack = client.placeOrder(item.sku(), order.getCases(),
                    order.getBuyerRef(), order.getRequestId());
            order.setPoNumber(ack.poNumber());
            order.setStatus(StatusMapper.fromLegacyCode(ack.statusCode()));
            repository.save(order);
            log.info("Placed supplier order " + ack.poNumber() + " for " + order.getBuyerRef()
                    + " (" + order.getCases() + " case(s) of " + item.sku() + ")");
            return true;
        } catch (LegacySupplyException e) {
            if (e.isRetryable()) {
                // outage / slow / refused: keep the reorder, the job retries it
                order.setFailure(e.getCode() + ": " + e.getMessage());
                repository.save(order);
                log.warn("Supplier unavailable, " + order.getBuyerRef() + " stays PENDING ("
                        + e.getCode() + ")");
            } else {
                fail(order, e.getCode() + ": " + e.getMessage());
            }
            return false;
        }
    }

    private void adopt(SupplierOrder order, LegacyXml.OrderStatus status) {
        order.setPoNumber(status.poNumber());
        order.setStatus(StatusMapper.fromLegacyCode(status.statusCode()));
        repository.save(order);
    }

    private void fail(SupplierOrder order, String reason) {
        order.setStatus(ReorderStatus.FAILED);
        order.setFailure(reason);
        repository.save(order);
        log.error("Reorder " + order.getBuyerRef() + " failed permanently: " + reason);
    }

    private ReorderResult toResult(SupplierOrder order) {
        return new ReorderResult(order.getId(), order.getProductId(), order.getStatus(),
                order.getUnits(), order.getCases(), order.getBuyerRef(), order.getRequestId(),
                order.getPoNumber(), order.getFailure());
    }
}