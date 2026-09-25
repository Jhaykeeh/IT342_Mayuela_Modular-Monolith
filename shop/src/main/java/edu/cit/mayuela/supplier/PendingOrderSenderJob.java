package edu.cit.mayuela.supplier;

import java.time.Duration;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retries reorders that are still PENDING. Runs every 60 seconds and checks
 * that nothing is retried more than once per minute, so a prolonged supplier
 * outage cannot hammer the request quota.
 *
 * Every send goes through {@link SupplierServiceImpl#attemptSend(SupplierOrder)},
 * which reuses the stored X-Request-Id and looks the BuyerRef up first, so no
 * retry can ever create a duplicate purchase order.
 */
@Component
class PendingOrderSenderJob {

    private static final Duration MIN_GAP = Duration.ofSeconds(60);

    private final SupplierOrderRepository repository;
    private final SupplierServiceImpl gateway;
    private final org.slf4j.Logger log = SupplierLogger.get();

    PendingOrderSenderJob(SupplierOrderRepository repository, SupplierServiceImpl gateway) {
        this.repository = repository;
        this.gateway = gateway;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
    public void sendPending() {
        Instant cutoff = Instant.now().minus(MIN_GAP);
        int sent = 0;
        for (SupplierOrder order : repository.findByStatusOrderByUpdatedAtAsc(ReorderStatus.PENDING)) {
            if (order.getUpdatedAt() != null && order.getUpdatedAt().isAfter(cutoff)) {
                continue;
            }
            if (gateway.attemptSend(order)) {
                sent++;
            }
        }
        if (sent > 0) {
            log.info("Retry job delivered " + sent + " pending reorder(s) to LegacySupply");
        }
    }
}