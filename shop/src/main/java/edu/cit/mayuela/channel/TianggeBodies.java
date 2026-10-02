package edu.cit.mayuela.channel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * The Tiangge wire format, in one place.
 *
 * Everything here is package-private and exists only to be serialised to or
 * from the marketplace. Nothing outside this package sees a Tiangge field name,
 * which is what keeps the vocabulary of the channel from leaking into the Order
 * or Inventory modules.
 *
 * Every response type ignores unknown fields, so Tiangge can add information to
 * the feed without this application having to change.
 */
final class TianggeBodies {

    private TianggeBodies() {
    }

    /**
     * POST /instances/heartbeat
     *
     * {@code startedAt} is a preformatted ISO-8601 instant rather than an
     * {@code Instant} so the value on the wire is exactly what Tiangge documents,
     * with no dependence on how a JSON mapper is configured to print dates.
     */
    record Heartbeat(String appName, String startedAt, long uptimeSeconds) {
    }

    /** One entry of PUT /listings */
    record Listing(String sellerSku, String title, String supplierSku) {
    }

    /** One entry of PUT /stock */
    record Stock(String sellerSku, int available) {
    }

    /**
     * POST /orders/{orderId}/decision
     *
     * {@code shopOrderId} is a string, as Tiangge documents it
     * ({@code "SO-20311"}), not the numeric primary key. It is opaque to the
     * marketplace; the prefix only makes it recognisable in their logs.
     *
     * The reason is omitted rather than sent as null, and shopOrderId is omitted
     * for a backorder, because no local order exists yet at that point.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Decision(String decision, String shopOrderId, String reason) {
    }

    /** POST /orders/{orderId}/resolution - only valid for a backorder. */
    record Resolution(String status) {
    }

    /** POST /orders/{orderId}/cancellation */
    record Cancellation(boolean restocked) {
    }

    /** GET /feed */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record FeedPage(List<Event> events, Long nextCursor) {
    }

    /**
     * One feed event. Both event types share this shape because Tiangge sends
     * the fields that apply and omits the rest, and the discriminator is the
     * {@code type} field.
     *
     * @param type             ORDER_PLACED or ORDER_CANCELLED
     * @param seq              the feed position, used as the cursor
     * @param eventId          the delivery id; processing it twice is a no-op
     * @param orderId          the marketplace order id
     * @param placedAt         when the customer placed the order (placed only)
     * @param decisionDeadline latest moment a decision is still accepted
     * @param lines            requested lines (placed only)
     * @param cancelledAt      when the customer cancelled (cancelled only)
     * @param confirmDeadline  latest moment a restock confirmation is accepted
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Event(String type,
                 Long seq,
                 String eventId,
                 String orderId,
                 Instant placedAt,
                 Instant decisionDeadline,
                 List<Line> lines,
                 Instant cancelledAt,
                 Instant confirmDeadline) {

        boolean isOrderPlaced() {
            return "ORDER_PLACED".equals(type);
        }

        boolean isOrderCancelled() {
            return "ORDER_CANCELLED".equals(type);
        }

        /** True once the decision deadline has passed; used for evidence only. */
        boolean pastDecisionDeadline() {
            return decisionDeadline != null && Instant.now().isAfter(decisionDeadline);
        }
    }

    /** One requested line of a marketplace order. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Line(String sellerSku, int qty) {
    }

    /** GET /orders/{orderId} - Tiangge's view of an order, for debugging. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record OrderView(String orderId,
                     String status,
                     Instant placedAt,
                     Instant decisionDeadline,
                     List<Line> lines) {
    }
}