package edu.cit.mayuela.channel;

import edu.cit.mayuela.shop.OrderService;
import edu.cit.mayuela.shop.OrderService.OrderItemRequest;
import edu.cit.mayuela.shop.OrderService.OrderResult;
import edu.cit.mayuela.supplier.OpenSupply;
import edu.cit.mayuela.supplier.SupplierOrderDeliveredEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns a backorder into an answer.
 *
 * A backorder was only promised because the supplier module confirmed that units
 * were on their way. This class watches for that arrival and, as soon as the
 * delivery has actually been restocked, tries again - oldest backorder first, so
 * the customer who waited longest is served first.
 *
 * The retry goes through {@link OrderService#placeOrder} once more, which means
 * a backorder is served under exactly the same all-or-nothing rule as any other
 * order: it either becomes a real confirmed order or it stays unresolved.
 *
 * A backorder ends in one of two ways:
 *  - ACCEPTED, the moment the lines can be reserved, and
 *  - CANCELLED, when the units are not coming after all, or when it has been
 *    waiting longer than {@link #MAX_WAIT} and the customer should not be left
 *    waiting indefinitely.
 *
 * Either way the outcome is recorded as a {@code RESOLUTION} outbox message, so
 * Tiangge is told even if the application restarts first.
 */
@Component
class BackorderService {

    /** How long a backorder may be kept before the customer gives up on it. */
    static final Duration MAX_WAIT = Duration.ofMinutes(10);

    private final OrderService orderService;
    private final OpenSupply openSupply;
    private final ChannelStore store;
    private final StockSync stockSync;
    private final Outbox outbox;
    private final ChannelStatus status;
    private final TransactionTemplate tx;
    private final Logger log = ChannelLogger.get();

    BackorderService(OrderService orderService,
                     OpenSupply openSupply,
                     ChannelStore store,
                     StockSync stockSync,
                     Outbox outbox,
                     ChannelStatus status,
                     TransactionTemplate tx) {
        this.orderService = orderService;
        this.openSupply = openSupply;
        this.store = store;
        this.stockSync = stockSync;
        this.outbox = outbox;
        this.status = status;
        this.tx = tx;
    }

    /**
     * A delivery arrived. The listener runs after that delivery's transaction
     * committed, so the stock it restocked is already visible here.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onDelivered(SupplierOrderDeliveredEvent event) {
        log.info("Delivery {} of {} unit(s) of {} received; retrying open backorders",
                event.poNumber(), event.units(), event.productId());
        retryPending();
    }

    /**
     * Periodic safety net.
     *
     * The delivery listener is the fast path, but it can be missed - a delivery
     * that arrives while the application is down, or a purchase order that is
     * placed only after the backorder was created. This sweep therefore also
     * serves the wait-time limit, and it is the only reason a backorder is ever
     * given up on.
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    void sweep() {
        retryPending();
    }

    /** Retries every open backorder, oldest first. */
    void retryPending() {
        if (!status.online()) {
            return;
        }
        List<OrderLink> open;
        try {
            open = store.openBackorders();
        } catch (RuntimeException e) {
            // Reported and retried on the next sweep: an unreadable table must not
            // stop the application, and a backorder that is missed here is picked
            // up by the sweep or the next supplier delivery.
            log.warn("Could not read the open backorders ({}); retrying in 30s", e.getMessage());
            return;
        }
        for (OrderLink link : open) {
            settle(link);
        }
    }

    private void settle(OrderLink link) {
        // The hold spans the whole answer: stock is not published before Tiangge
        // has been told how the backorder ended.
        stockSync.hold();
        boolean released = false;
        try {
            OrderLink settled = tx.execute(txStatus -> {
                // Re-read inside the transaction: another sweep or the delivery
                // listener may already have resolved this one.
                Optional<OrderLink> current = store.findLink(link.tianggeOrderId());
                if (current.isEmpty() || !"BACKORDERED".equals(current.get().state())) {
                    return null;
                }
                OrderLink backorder = current.get();
                List<OrderItemRequest> lines = toRequests(backorder);

                // Only attempt a real order once the units look available, so a
                // backorder that is still waiting does not leave a trail of
                // rejected orders in the shop's history.
                if (orderService.canFill(lines)) {
                    OrderResult result = orderService.placeOrder(lines);
                    if ("CONFIRMED".equals(result.status())) {
                        settleAccepted(backorder, result);
                        return backorder;
                    }
                    // Lost a race against another order: fall through and judge
                    // the backorder on its own merits below.
                }
                settleCancelled(backorder, lines);
                return backorder;
            });

            if (settled != null) {
                outbox.deliver(settled.tianggeOrderId());
            }
            stockSync.release();
            released = true;
        } catch (RuntimeException e) {
            log.warn("Backorder {} could not be retried this round: {}",
                    link.tianggeOrderId(), e.getMessage());
        } finally {
            if (!released) {
                stockSync.release();
            }
        }
    }

    private void settleAccepted(OrderLink backorder, OrderResult result) {
        store.updateLink(backorder.tianggeOrderId(), "ACCEPTED", Outbox.RESOLUTION, result.orderId());
        log.info("Backorder chain complete: Tiangge order {} resolved ACCEPTED as shopOrderId={}",
                backorder.tianggeOrderId(), result.orderId());
    }

    /**
     * Gives up on a backorder, but only for a good reason: the units are not
     * coming, or the customer has waited long enough.
     */
    private void settleCancelled(OrderLink backorder, List<OrderItemRequest> lines) {
        String reason = giveUpReason(backorder, lines);
        if (reason == null) {
            log.info("Backorder {} still short and still covered; keeping it open", backorder.tianggeOrderId());
            return;
        }
        store.updateLink(backorder.tianggeOrderId(), "CANCELLED", Outbox.RESOLUTION, null);
        log.info("Backorder chain ended: Tiangge order {} resolved CANCELLED ({})",
                backorder.tianggeOrderId(), reason);
    }

    /** @return why the backorder must be given up on, or null to keep waiting */
    private String giveUpReason(OrderLink backorder, List<OrderItemRequest> lines) {
        for (OrderItemRequest line : lines) {
            if (!openSupply.ensureCovered(line.productId(), line.quantity())) {
                return "no restock coming for " + line.productId();
            }
        }
        Instant placedAt = backorder.placedAt();
        if (placedAt != null && Duration.between(placedAt, Instant.now()).compareTo(MAX_WAIT) >= 0) {
            return "waited longer than " + MAX_WAIT.toMinutes() + " minutes";
        }
        return null;
    }

    private List<OrderItemRequest> toRequests(OrderLink link) {
        List<OrderItemRequest> requests = new ArrayList<>();
        for (TianggeBodies.Line line : store.readLines(link.lines())) {
            requests.add(new OrderItemRequest(line.sellerSku(), Math.max(line.qty(), 1)));
        }
        return requests;
    }
}