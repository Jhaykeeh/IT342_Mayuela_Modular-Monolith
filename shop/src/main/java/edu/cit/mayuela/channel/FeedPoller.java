package edu.cit.mayuela.channel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reads the marketplace order feed.
 *
 * The loop is the durable half of at-least-once delivery:
 *
 *  1. read the cursor that was persisted by the previous run,
 *  2. fetch one page after it,
 *  3. handle the events oldest first,
 *  4. save the cursor, then
 *  5. repeat while a full page came back, so a backlog is drained in one go.
 *
 * The cursor only moves after a whole page has been handled. If a handler
 * fails, the batch stops there and the cursor stays where it was, so the same
 * event is offered again next time. That is safe because every handler claims
 * its event id first: a redelivered event is recognised and skipped instead of
 * being applied twice. Together with the orderId guard this is what makes a
 * restart catch up without processing anything twice.
 */
@Component
class FeedPoller {

    private final ChannelStore store;
    private final TianggeClient client;
    private final ChannelStatus status;
    private final OrderIntake intake;
    private final CancellationHandler cancellations;
    private final Logger log = ChannelLogger.get();

    FeedPoller(ChannelStore store,
               TianggeClient client,
               ChannelStatus status,
               OrderIntake intake,
               CancellationHandler cancellations) {
        this.store = store;
        this.client = client;
        this.status = status;
        this.intake = intake;
        this.cancellations = cancellations;
    }

    /** Every three seconds, so a decision lands well inside its 60s deadline. */
    @Scheduled(fixedDelay = 3_000, initialDelay = 5_000)
    void poll() {
        if (!status.online()) {
            return;
        }
        try {
            drain();
        } catch (RuntimeException e) {
            // Nothing is lost: the cursor did not move past the failing event.
            log.warn("Feed poll stopped at an event that could not be handled: {}", e.getMessage());
        }
    }

    private void drain() {
        while (true) {
            long cursor = store.readCursor();
            TianggeBodies.FeedPage page = client.fetchFeed(cursor, TianggeClient.FEED_LIMIT);
            List<TianggeBodies.Event> events = page.events();
            if (events == null || events.isEmpty()) {
                log.debug("Feed caught up at cursor {}", cursor);
                return;
            }

            for (TianggeBodies.Event event : oldestFirst(events)) {
                if (!handle(event)) {
                    // Stop the batch and leave the cursor where it is: this event
                    // was not claimed, so nothing partial survived and it must be
                    // offered again rather than skipped.
                    log.error("Halting the feed batch at event {} (order {}); cursor stays at {}",
                            event.eventId(), event.orderId(), cursor);
                    return;
                }
            }

            long next = nextCursor(page, events, cursor);
            store.writeCursor(next);
            log.info("Processed {} event(s) from the feed; cursor {} -> {}",
                    events.size(), cursor, next);

            if (events.size() < TianggeClient.FEED_LIMIT) {
                return;
            }
        }
    }

    /** @return true when the event is settled and the cursor may move past it */
    private boolean handle(TianggeBodies.Event event) {
        if (event.eventId() == null || event.orderId() == null) {
            log.warn("Ignoring feed event without an id: {}", event);
            return true;
        }
        if (event.isOrderPlaced()) {
            return intake.onOrderPlaced(event);
        }
        if (event.isOrderCancelled()) {
            return cancellations.onOrderCancelled(event);
        }
        log.warn("Ignoring unknown feed event type '{}' for order {}", event.type(), event.orderId());
        return true;
    }

    private List<TianggeBodies.Event> oldestFirst(List<TianggeBodies.Event> events) {
        List<TianggeBodies.Event> ordered = new ArrayList<>(events);
        ordered.sort(Comparator.comparing(
                event -> event.seq() == null ? Long.MAX_VALUE : event.seq()));
        return ordered;
    }

    /**
     * Where to resume next.
     *
     * Tiangge's own cursor wins. The highest sequence number seen is only a
     * fallback, so a page that omits the cursor still makes progress instead of
     * being read again forever.
     */
    private long nextCursor(TianggeBodies.FeedPage page, List<TianggeBodies.Event> events, long fallback) {
        Long reported = page.nextCursor();
        if (reported != null) {
            return reported;
        }
        return events.stream()
                .map(TianggeBodies.Event::seq)
                .filter(Objects::nonNull)
                .max(Long::compareTo)
                .orElse(fallback);
    }
}