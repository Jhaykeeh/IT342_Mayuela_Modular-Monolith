package edu.cit.mayuela.channel;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Durable state for the channel, in three tables created by
 * {@code channel-schema.sql}.
 *
 *  - {@code channel_cursor}             where the feed was last read up to
 *  - {@code channel_processed_event}    every eventId already handled
 *  - {@code channel_order_link}         one row per marketplace order
 *
 * This is the machinery behind the two guarantees the lab asks for. The cursor
 * means a restart resumes instead of replaying the whole feed, and the two
 * unique keys mean a replayed event is recognised and skipped rather than
 * applied twice.
 *
 * Every method takes part in the caller's transaction. That is deliberate: the
 * claim of an eventId, the creation of the local order and the writing of the
 * link row must either all become visible together or not at all, which is what
 * makes "exactly one order per marketplace orderId" true even if the process
 * dies mid-handler.
 */
@Component
class ChannelStore {

    private static final String SELECT_LINK = """
            select tiangge_order_id, shop_order_id, state, lines, placed_at, outbox, created_at
            from channel_order_link
            """;

    private final JdbcClient jdbc;
    private final Logger log = ChannelLogger.get();

    ChannelStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    // ---- feed cursor ------------------------------------------------------

    /** The feed position already processed; 0 when this is a first run. */
    long readCursor() {
        return jdbc.sql("select last_seq from channel_cursor where id = 1")
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    /** Persists progress so a restart resumes from exactly here. */
    void writeCursor(long seq) {
        jdbc.sql("insert into channel_cursor (id, last_seq) values (1, :seq) "
                        + "on conflict (id) do update set last_seq = excluded.last_seq")
                .param("seq", seq)
                .update();
    }

    // ---- exactly-once -----------------------------------------------------

    /**
     * Claims an event for processing.
     *
     * Returns false when this eventId has been claimed before, which is how a
     * redelivered feed event becomes a no-op. The claim is part of the caller's
     * transaction, so it disappears again if the handler rolls back and the
     * event is retried rather than silently lost.
     */
    boolean claimEvent(String eventId) {
        int inserted = jdbc.sql("insert into channel_processed_event (event_id) values (:eventId) "
                        + "on conflict (event_id) do nothing")
                .param("eventId", eventId)
                .update();
        return inserted > 0;
    }

    // ---- order links ------------------------------------------------------

    Optional<OrderLink> findLink(String tianggeOrderId) {
        return jdbc.sql(SELECT_LINK + "where tiangge_order_id = :id")
                .param("id", tianggeOrderId)
                .query(ChannelStore::mapLink)
                .optional();
    }

    boolean linkExists(String tianggeOrderId) {
        return findLink(tianggeOrderId).isPresent();
    }

    /**
     * Every bind names its SQL type.
     *
     * The driver refuses to guess: an {@code Instant} has no mapping it can
     * infer, and a null {@code Long} or null timestamp has none either. Being
     * explicit also keeps the stored instant unambiguous - the value is handed
     * over as UTC, so it does not depend on the timezone of the JVM or of the
     * database session.
     */
    void insertLink(OrderLink link) {
        jdbc.sql("insert into channel_order_link "
                        + "(tiangge_order_id, shop_order_id, state, lines, placed_at, outbox, created_at) "
                        + "values (:orderId, :shopOrderId, :state, :lines, :placedAt, :outbox, now())")
                .param("orderId", link.tianggeOrderId(), Types.VARCHAR)
                .param("shopOrderId", link.shopOrderId(), Types.BIGINT)
                .param("state", link.state(), Types.VARCHAR)
                .param("lines", link.lines(), Types.VARCHAR)
                .param("placedAt", utc(link.placedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("outbox", link.outbox(), Types.VARCHAR)
                .update();
    }

    /**
     * Moves an order to a new state and records which message now owes Tiangge
     * an answer. Passing a null outbox marks the order as fully settled.
     */
    void updateLink(String tianggeOrderId, String state, String outbox, Long shopOrderId) {
        jdbc.sql("update channel_order_link set state = :state, outbox = :outbox, "
                        + "shop_order_id = coalesce(:shopOrderId, shop_order_id) "
                        + "where tiangge_order_id = :orderId")
                .param("state", state, Types.VARCHAR)
                .param("outbox", outbox, Types.VARCHAR)
                .param("shopOrderId", shopOrderId, Types.BIGINT)
                .param("orderId", tianggeOrderId, Types.VARCHAR)
                .update();
    }

    /** An instant as an unambiguous UTC timestamp for a TIMESTAMPTZ column. */
    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    /** Clears the outbox once a message has been acknowledged by Tiangge. */
    void clearOutbox(String tianggeOrderId) {
        jdbc.sql("update channel_order_link set outbox = null where tiangge_order_id = :orderId")
                .param("orderId", tianggeOrderId)
                .update();
    }

    /** Every order still owing a message, oldest first. Drives the outbox sweeper. */
    List<OrderLink> pendingOutbox() {
        return jdbc.sql(SELECT_LINK + "where outbox is not null order by created_at asc")
                .query(ChannelStore::mapLink)
                .list();
    }

    /** Every open backorder, oldest first, so the oldest is served first. */
    List<OrderLink> openBackorders() {
        return jdbc.sql(SELECT_LINK + "where state = 'BACKORDERED' order by placed_at asc nulls last")
                .query(ChannelStore::mapLink)
                .list();
    }

    // ---- line serialisation ----------------------------------------------

    /**
     * Lines are stored as {@code SKUxQTY,SKUxQTY} rather than JSON.
     *
     * The column only ever holds data this class wrote, so a tiny encoder is
     * cheaper and safer here than a JSON dependency: no object mapper to wire,
     * nothing to deserialise that could fail in a way that loses an order.
     */
    String writeLines(List<TianggeBodies.Line> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (TianggeBodies.Line line : lines) {
            if (text.length() > 0) {
                text.append(',');
            }
            text.append(line.sellerSku()).append('x').append(line.qty());
        }
        return text.toString();
    }

    List<TianggeBodies.Line> readLines(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<TianggeBodies.Line> lines = new ArrayList<>();
        for (String part : text.split(",")) {
            if (part.isBlank()) {
                continue;
            }
            int split = part.lastIndexOf('x');
            if (split <= 0) {
                log.warn("Ignoring unreadable stored line '{}'", part);
                continue;
            }
            try {
                lines.add(new TianggeBodies.Line(
                        part.substring(0, split), Integer.parseInt(part.substring(split + 1))));
            } catch (NumberFormatException e) {
                log.warn("Ignoring stored line with a bad quantity: '{}'", part);
            }
        }
        return lines;
    }

    private static OrderLink mapLink(ResultSet rs, int row) throws SQLException {
        long rawOrderId = rs.getLong("shop_order_id");
        Long shopOrderId = rs.wasNull() ? null : rawOrderId;
        return new OrderLink(
                rs.getString("tiangge_order_id"),
                shopOrderId,
                rs.getString("state"),
                rs.getString("lines"),
                instant(rs, "placed_at"),
                rs.getString("outbox"),
                instant(rs, "created_at"));
    }

    /** TIMESTAMPTZ read as an Instant, staying null-safe. */
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}