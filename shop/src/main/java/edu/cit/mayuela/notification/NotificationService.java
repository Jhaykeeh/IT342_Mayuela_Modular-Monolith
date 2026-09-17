package edu.cit.mayuela.notification;

import edu.cit.mayuela.shop.LowStockEvent;
import edu.cit.mayuela.shop.OrderConfirmedEvent;
import edu.cit.mayuela.shop.OrderRejectedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Reacts to domain events published by the Order module. This is the only
 * dependency this module has on the shop package — it deliberately never calls
 * InventoryService or OrderService, keeping the dependency arrow one-way.
 *
 * Listeners run synchronously in the publisher's thread. This was kept
 * synchronous (no {@code @Async}) so that a notification is guaranteed to be
 * written before the HTTP response returns, making the activity feed and the
 * order result consistent within a single request/response cycle.
 */
@Service
public class NotificationService {

    private final NotificationRepository repository;

    public NotificationService(NotificationRepository repository) {
        this.repository = repository;
    }

    @EventListener
    public void onOrderConfirmed(OrderConfirmedEvent event) {
        repository.save(new Notification("Order O" + event.orderId() + " confirmed"));
    }

    @EventListener
    public void onOrderRejected(OrderRejectedEvent event) {
        repository.save(new Notification("Order O" + event.orderId() + " rejected: " + event.reason()));
    }

    @EventListener
    public void onLowStock(LowStockEvent event) {
        repository.save(new Notification("Reorder needed: " + event.productId()
                + " below threshold (" + event.remainingStock() + " remaining)"));
    }
}