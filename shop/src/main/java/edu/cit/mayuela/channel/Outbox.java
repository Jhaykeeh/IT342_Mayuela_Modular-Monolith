package edu.cit.mayuela.channel;

import java.util.List;
import org.slf4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every message this application owes Tiangge is sent from here.
 *
 * A message is never sent as a side effect of handling an event. Instead the
 * pending message is recorded in the {@code outbox} column of
 * {@code channel_order_link}, and this class sends it. That split is what
 * makes a decision survive the process dying between "order committed" and
 * "Tiangge told": the sweeper finds the row still owing a message and sends it
 * again, with identical content, which the API accepts as an idempotent replay.
 *
 * The body of each message is derived from the row rather than stored, so the
 * row can never disagree with what was actually sent:
 *  - a decision is the state itself (ACCEPTED, REJECTED, BACKORDERED),
 *  - a resolution is ACCEPTED when the order ended up filled, otherwise
 *    CANCELLED,
 *  - a cancellation confirmation is always the same fixed body.
 */
@Component
class Outbox {

    static final String DECISION = "DECISION";

    static final String RESOLUTION = "RESOLUTION";

    static final String CANCEL_CONFIRM = "CANCEL_CONFIRM";

    private final ChannelStore store;
    private final TianggeClient client;
    private final ChannelStatus status;
    private final Logger log = ChannelLogger.get();

    Outbox(ChannelStore store, TianggeClient client, ChannelStatus status) {
        this.store = store;
        this.client = client;
        this.status = status;
    }

    /** Sends whatever is owed for one order right now; a no-op once settled. */
    void deliver(String tianggeOrderId) {
        store.findLink(tianggeOrderId).ifPresent(this::sendQuietly);
    }

    /**
     * Resends anything still owed, every two seconds. This is the only periodic
     * retry in the channel, and it covers both a failed send and a crash between
     * commit and send.
     */
    @Scheduled(fixedDelay = 2_000, initialDelay = 2_000)
    void sweep() {
        if (!status.online()) {
            return;
        }
        List<OrderLink> owed;
        try {
            owed = store.pendingOutbox();
        } catch (RuntimeException e) {
            // The channel is required to run unattended, so a problem reading the
            // table is reported and retried on the next tick rather than thrown
            // back at the scheduler. Nothing is lost: the outbox column is the
            // queue, and it is still set.
            log.warn("Could not read the outbox ({}); retrying in 2s", e.getMessage());
            return;
        }
        for (OrderLink link : owed) {
            sendQuietly(link);
        }
    }

    private void sendQuietly(OrderLink link) {
        try {
            send(link);
        } catch (RuntimeException e) {
            // The outbox column is left set, so the next sweep tries again.
            log.warn("Outbox {} for order {} still pending: {}",
                    link.outbox(), link.tianggeOrderId(), e.getMessage());
        }
    }

    private void send(OrderLink link) {
        String orderId = link.tianggeOrderId();
        String kind = link.outbox();
        if (kind == null) {
            return;
        }
        switch (kind) {
            case DECISION -> client.sendDecision(orderId, link.state(), link.shopOrderId(), reasonFor(link));
            case RESOLUTION -> client.sendResolution(orderId,
                    "ACCEPTED".equals(link.state()) ? "ACCEPTED" : "CANCELLED");
            case CANCEL_CONFIRM -> client.sendCancellation(orderId);
            default -> log.warn("Unknown outbox kind '{}' for order {}; clearing it", kind, orderId);
        }
        store.clearOutbox(orderId);
        log.info("Sent {} for Tiangge order {} (shopOrderId={}, state={})",
                kind, orderId, sentReference(kind, link), link.state());
    }

    /**
     * What Tiangge was actually told, rather than what the link row holds.
     *
     * A backorder has no local order, so the row shows null while the decision
     * still carries the placeholder Tiangge requires. Logging the row value alone
     * would read as a broken decision.
     */
    private static Object sentReference(String kind, OrderLink link) {
        if (!DECISION.equals(kind) || link.shopOrderId() != null) {
            return link.shopOrderId();
        }
        return "SO-PENDING-" + link.tianggeOrderId();
    }

    private String reasonFor(OrderLink link) {
        return switch (link.state()) {
            case "REJECTED" -> "Insufficient stock and no restock is on its way";
            case "BACKORDERED" -> "Awaiting supplier delivery";
            default -> null;
        };
    }
}