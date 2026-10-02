package edu.cit.mayuela.channel;

import edu.cit.mayuela.inventory.Inventory;
import edu.cit.mayuela.inventory.InventoryService;
import edu.cit.mayuela.shop.OrderService;
import edu.cit.mayuela.shop.OrderService.OrderItemRequest;
import edu.cit.mayuela.shop.OrderService.OrderResult;
import edu.cit.mayuela.supplier.OpenSupply;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns one {@code ORDER_PLACED} event into one local order and one decision.
 *
 * The order is created by calling {@link OrderService#placeOrder} - the very
 * same method the React UI calls. A marketplace customer and a browser customer
 * therefore compete for stock through identical rules, and neither can buy what
 * the other has taken.
 *
 * Exactly-once rests on three things happening inside a single database
 * transaction: the claim of the eventId, the creation of the local order, and
 * the writing of the link row. If the process dies at any point in the middle,
 * all three disappear together and the event is simply processed again on the
 * next poll - never half applied. A second guard, on the orderId, catches the
 * case where the same order arrives with a fresh eventId.
 *
 * The three decisions:
 *  - ACCEPTED    every line was reserved,
 *  - BACKORDERED some line was short, and for every short line the supplier
 *                module confirmed that units are already on their way (or just
 *                placed a purchase order to make that true),
 *  - REJECTED    some line was short and nothing is coming.
 *
 * An order is never rejected while it could still be filled, which is why the
 * supplier is asked before the decision rather than after it.
 */
@Component
class OrderIntake {

    private final OrderService orderService;
    private final InventoryService inventory;
    private final OpenSupply openSupply;
    private final ChannelStore store;
    private final StockSync stockSync;
    private final Outbox outbox;
    private final TransactionTemplate tx;
    private final Logger log = ChannelLogger.get();

    OrderIntake(OrderService orderService,
                InventoryService inventory,
                OpenSupply openSupply,
                ChannelStore store,
                StockSync stockSync,
                Outbox outbox,
                TransactionTemplate tx) {
        this.orderService = orderService;
        this.inventory = inventory;
        this.openSupply = openSupply;
        this.store = store;
        this.stockSync = stockSync;
        this.outbox = outbox;
        this.tx = tx;
    }

    /**
     * Handles one placed order.
     *
     * @return true when the event is dealt with and the feed may move past it;
     *         false when an unexpected failure left the event unclaimed, in
     *         which case the poller must not advance the cursor
     */
    boolean onOrderPlaced(TianggeBodies.Event event) {
        stockSync.hold();
        boolean released = false;
        try {
            OrderLink link = createOnce(event);
            if (link == null) {
                log.info("Skipped duplicate event {} for Tiangge order {}", event.eventId(), event.orderId());
            } else {
                logDecision(event, link);
                outbox.deliver(link.tianggeOrderId());
            }
            stockSync.release();
            released = true;
            return true;
        } catch (RuntimeException e) {
            // The event is not claimed, so nothing partial survived and the same
            // event will be offered again on the next poll.
            log.error("Failed to process Tiangge order {} (event {}); will retry it: {}",
                    event.orderId(), event.eventId(), e.getMessage(), e);
            return false;
        } finally {
            if (!released) {
                stockSync.release();
            }
        }
    }

    /**
     * Claims, orders and records - all or nothing.
     *
     * @return the recorded link, or null when this event or order was already
     *         handled
     */
    private OrderLink createOnce(TianggeBodies.Event event) {
        return tx.execute(status -> {
            if (!store.claimEvent(event.eventId())) {
                return null;
            }
            if (store.linkExists(event.orderId())) {
                log.info("Tiangge order {} already has a link row; not creating a second order",
                        event.orderId());
                return null;
            }

            List<OrderItemRequest> lines = toItemRequests(event.lines());
            if (lines.isEmpty()) {
                log.warn("Tiangge order {} arrived without any line; rejecting it", event.orderId());
                store.insertLink(new OrderLink(event.orderId(), null, "REJECTED",
                        store.writeLines(event.lines()), event.placedAt(), Outbox.DECISION, Instant.now()));
                return store.findLink(event.orderId()).orElseThrow();
            }

            OrderResult result = orderService.placeOrder(lines);

            String state;
            Long shopOrderId = result.orderId();
            if ("CONFIRMED".equals(result.status())) {
                state = "ACCEPTED";
            } else if (restockIsComing(event, result)) {
                state = "BACKORDERED";
                shopOrderId = null;
            } else {
                state = "REJECTED";
            }

            OrderLink link = new OrderLink(event.orderId(), shopOrderId, state,
                    store.writeLines(event.lines()), event.placedAt(), Outbox.DECISION, Instant.now());
            store.insertLink(link);
            return link;
        });
    }

    /**
     * Asks the supplier module about every short line.
     *
     * @return true only when every short item has an open purchase order, which
     *         is the definition of a backorder this application can honour
     */
    private boolean restockIsComing(TianggeBodies.Event event, OrderResult result) {
        List<TianggeBodies.Line> lines = event.lines();
        if (lines == null || lines.isEmpty()) {
            return false;
        }
        for (TianggeBodies.Line line : lines) {
            if (isReserved(result, line.sellerSku())) {
                continue;
            }
            int available = inventory.getItem(line.sellerSku()).map(Inventory::getStock).orElse(0);
            int missing = Math.max(1, line.qty() - available);
            if (!openSupply.ensureCovered(line.sellerSku(), missing)) {
                log.warn("Rejecting Tiangge order {}: nothing is coming for {}",
                        event.orderId(), line.sellerSku());
                return false;
            }
        }
        return true;
    }

    private boolean isReserved(OrderResult result, String sellerSku) {
        return result.items().stream()
                .anyMatch(outcome -> outcome.productId().equals(sellerSku) && "RESERVED".equals(outcome.outcome()));
    }

    private List<OrderItemRequest> toItemRequests(List<TianggeBodies.Line> lines) {
        List<OrderItemRequest> requests = new ArrayList<>();
        if (lines == null) {
            return requests;
        }
        for (TianggeBodies.Line line : lines) {
            requests.add(new OrderItemRequest(line.sellerSku(), Math.max(line.qty(), 1)));
        }
        return requests;
    }

    /**
     * The evidence line for every decision, including how late it was.
     *
     * {@code MISSED} is Tiangge's own verdict on the event, so this line can
     * never claim success for an order the marketplace counted as late - the
     * earlier wording printed "PASSED" for exactly that case, which read as the
     * opposite of what happened.
     */
    private void logDecision(TianggeBodies.Event event, OrderLink link) {
        long latency = event.placedAt() == null
                ? -1
                : Duration.between(event.placedAt(), Instant.now()).toMillis();
        log.info("Tiangge order {} -> {} shopOrderId={} latencyMs={} deadline={} eventId={}",
                event.orderId(), link.state(), link.shopOrderId(), latency,
                event.pastDecisionDeadline() ? "MISSED" : "met", event.eventId());
    }
}