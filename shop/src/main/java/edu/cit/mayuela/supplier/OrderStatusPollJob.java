package edu.cit.mayuela.supplier;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls the supplier for the status of every open purchase order and maps its
 * numeric codes to this system's {@link ReorderStatus}.
 *
 * Unexpected status codes become {@link ReorderStatus#UNKNOWN}, which excludes
 * the order from polling, never restocks it and keeps it visible for manual
 * review (see INTEGRATION.md).
 *
 * Requests are throttled: each order is polled at most once a minute, keeping
 * the adapter comfortably inside LegacySupply's request quota.
 */
@Component
class OrderStatusPollJob {

    private static final Duration MIN_GAP = Duration.ofSeconds(60);

    private final SupplierOrderRepository repository;
    private final LegacySupplyClient client;
    private final SupplierOrderUpdater updater;
    private final org.slf4j.Logger log = SupplierLogger.get();

    OrderStatusPollJob(SupplierOrderRepository repository,
                       LegacySupplyClient client,
                       SupplierOrderUpdater updater) {
        this.repository = repository;
        this.client = client;
        this.updater = updater;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void pollOpenOrders() {
        Instant cutoff = Instant.now().minus(MIN_GAP);
        for (SupplierOrder order : repository.findByPoNumberNotNullAndStatusInOrderByUpdatedAtAsc(
                SupplierOrderUpdater.trackedStatuses())) {
            if (order.getUpdatedAt().isAfter(cutoff)) {
                continue;
            }
            try {
                LegacyXml.OrderStatus doc = client.fetchOrder(order.getPoNumber());
                ReorderStatus mapped = StatusMapper.fromLegacyCode(doc.statusCode());
                updater.apply(order, mapped, doc.statusCode());
                log.info("PO " + order.getPoNumber() + " -> " + mapped
                        + " (legacy code " + doc.statusCode() + ")");
            } catch (LegacySupplyException e) {
                log.warn("Status poll failed for " + order.getPoNumber() + " (" + e.getCode()
                        + "); will retry later");
            }
        }
    }

    /** Kept for tests / introspection readouts. */
    static List<ReorderStatus> tracked() {
        return SupplierOrderUpdater.trackedStatuses();
    }
}