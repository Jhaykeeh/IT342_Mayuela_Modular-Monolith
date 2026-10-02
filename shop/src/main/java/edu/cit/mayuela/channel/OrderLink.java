package edu.cit.mayuela.channel;

import java.time.Instant;

/**
 * One marketplace order and this application's answer to it.
 *
 * The row is the whole story of an order across restarts: which local order it
 * became, what was decided, and which message still owes Tiangge an answer.
 *
 * @param tianggeOrderId the marketplace order id, and the natural key
 * @param shopOrderId    the local order, or null while nothing was ever created
 * @param state          ACCEPTED, REJECTED, BACKORDERED, CANCELLED or
 *                       CANCELLED_BY_CUSTOMER
 * @param lines          the requested lines, kept so a backorder can be retried
 *                       after a restart without refetching the feed
 * @param placedAt       when the customer placed the order
 * @param outbox         the message still owed to Tiangge: DECISION,
 *                       RESOLUTION, CANCEL_CONFIRM, or null when settled
 * @param createdAt      when this row was written
 */
record OrderLink(String tianggeOrderId,
                 Long shopOrderId,
                 String state,
                 String lines,
                 Instant placedAt,
                 String outbox,
                 Instant createdAt) {
}