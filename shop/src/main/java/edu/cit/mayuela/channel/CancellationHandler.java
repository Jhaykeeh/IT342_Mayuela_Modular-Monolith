package edu.cit.mayuela.channel;

import edu.cit.mayuela.shop.OrderService;
import edu.cit.mayuela.shop.OrderService.OrderAlreadyCancelledException;
import edu.cit.mayuela.shop.OrderService.OrderNotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles a customer cancelling an order that came from the marketplace.
 *
 * The cancellation itself is the Lab 2 path, {@link OrderService#cancelOrder},
 * so the reserved units go back to stock through exactly the same code the
 * React UI's Cancel button uses - no second restocking implementation to drift.
 *
 * Only the units this application is actually holding are given back. An order
 * that was rejected or is still backordered never reserved anything, so there is
 * nothing to return and the confirmation is sent on its own.
 *
 * The confirmation is recorded as an outbox message rather than sent inline, so
 * a failure or a crash before it went out is retried until Tiangge accepts it.
 */
@Component
class CancellationHandler {

    private final OrderService orderService;
    private final ChannelStore store;
    private final StockSync stockSync;
    private final Outbox outbox;
    private final TransactionTemplate tx;
    private final Logger log = ChannelLogger.get();

    CancellationHandler(OrderService orderService,
                        ChannelStore store,
                        StockSync stockSync,
                        Outbox outbox,
                        TransactionTemplate tx) {
        this.orderService = orderService;
        this.store = store;
        this.stockSync = stockSync;
        this.outbox = outbox;
        this.tx = tx;
    }

    /**
     * Handles one cancellation.
     *
     * @return true when the event is dealt with and the feed may move past it;
     *         false when an unexpected failure left the event unclaimed
     */
    boolean onOrderCancelled(TianggeBodies.Event event) {
        stockSync.hold();
        boolean released = false;
        try {
            OrderLink link = cancelOnce(event);
            if (link == null) {
                log.info("Skipped duplicate cancellation event {} for order {}",
                        event.eventId(), event.orderId());
            } else {
                long latency = event.cancelledAt() == null
                        ? -1
                        : Duration.between(event.cancelledAt(), Instant.now()).toMillis();
                log.info("Cancelled Tiangge order {} (shopOrderId={}) latencyMs={}",
                        event.orderId(), link.shopOrderId(), latency);
                outbox.deliver(link.tianggeOrderId());
            }
            stockSync.release();
            released = true;
            return true;
        } catch (RuntimeException e) {
            log.error("Failed to cancel Tiangge order {} (event {}); will retry it: {}",
                    event.orderId(), event.eventId(), e.getMessage(), e);
            return false;
        } finally {
            if (!released) {
                stockSync.release();
            }
        }
    }

    /**
     * Claims the event, cancels through the Order module and records the
     * confirmation owed to Tiangge - in one transaction.
     *
     * @return the updated link, or null when already handled
     */
    private OrderLink cancelOnce(TianggeBodies.Event event) {
        return tx.execute(status -> {
            if (!store.claimEvent(event.eventId())) {
                return null;
            }

            Optional<OrderLink> existing = store.findLink(event.orderId());
            if (existing.isEmpty()) {
                // Nothing was ever created for this order, so nothing is held.
                // Still confirm, otherwise Tiangge waits until its deadline.
                log.warn("Cancellation for unknown Tiangge order {}; confirming without restocking",
                        event.orderId());
                store.insertLink(new OrderLink(event.orderId(), null, "CANCELLED_BY_CUSTOMER",
                        "", null, Outbox.CANCEL_CONFIRM, Instant.now()));
                return store.findLink(event.orderId()).orElseThrow();
            }

            OrderLink link = existing.get();
            releaseUnits(link);
            store.updateLink(event.orderId(), "CANCELLED_BY_CUSTOMER", Outbox.CANCEL_CONFIRM, null);
            return new OrderLink(event.orderId(), link.shopOrderId(), "CANCELLED_BY_CUSTOMER",
                    link.lines(), link.placedAt(), Outbox.CANCEL_CONFIRM, link.createdAt());
        });
    }

    /**
     * Puts the reserved units back through the Order module.
     *
     * Only an accepted order ever held stock. A rejected or backordered order
     * has nothing to return, and neither has one whose local order has gone
     * missing; both are logged and still get a confirmation, because Tiangge
     * waits for one either way.
     */
    private void releaseUnits(OrderLink link) {
        if (!"ACCEPTED".equals(link.state())) {
            log.info("Order {} was {}; no stock was held for it", link.tianggeOrderId(), link.state());
            return;
        }
        try {
            orderService.cancelOrder(link.shopOrderId());
            log.info("Restocked the {} line(s) reserved by Tiangge order {}",
                    store.readLines(link.lines()).size(), link.tianggeOrderId());
        } catch (OrderAlreadyCancelledException e) {
            // The units are already back; the confirmation still has to go out.
            log.info("Order {} was already cancelled locally; confirming again", link.tianggeOrderId());
        } catch (OrderNotFoundException e) {
            log.warn("Local order {} for Tiangge order {} is missing; nothing to restock",
                    link.shopOrderId(), link.tianggeOrderId());
        }
    }
}