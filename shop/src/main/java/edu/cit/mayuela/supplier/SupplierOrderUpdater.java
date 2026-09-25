package edu.cit.mayuela.supplier;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies a supplier status change to a supplier_orders row and, when the
 * order turns out to be delivered, publishes the delivery domain event.
 *
 * The status update and the domain event are committed together: Inventory
 * restocks while listening to the same transaction, so a restock can never be
 * applied without the delivered status (and a delivered status can never be
 * stored without its restock).
 */
@Component
class SupplierOrderUpdater {

    private final SupplierOrderRepository repository;
    private final SupplierCatalog catalog;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    SupplierOrderUpdater(SupplierOrderRepository repository,
                         SupplierCatalog catalog,
                         org.springframework.context.ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.catalog = catalog;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    void apply(SupplierOrder order, ReorderStatus status, int statusCode) {
        boolean becameDelivered = status == ReorderStatus.DELIVERED
                && order.getStatus() != ReorderStatus.DELIVERED;
        order.setStatus(status);
        if (status == ReorderStatus.UNKNOWN) {
            order.setFailure("Unrecognised supplier status code " + statusCode);
        }
        repository.save(order);
        if (becameDelivered) {
            int deliveredUnits = order.getCases() * packSize(order);
            eventPublisher.publishEvent(new SupplierOrderDeliveredEvent(
                    order.getId(), order.getProductId(), deliveredUnits, order.getPoNumber()));
        }
    }

    private int packSize(SupplierOrder order) {
        SupplierItem item = catalog.find(order.getProductId());
        return item == null ? 1 : item.packSize();
    }

    /** Statuses still being tracked: anything the supplier has acked. */
    static List<ReorderStatus> trackedStatuses() {
        return List.of(ReorderStatus.ACCEPTED, ReorderStatus.PICKING, ReorderStatus.SHIPPED);
    }
}