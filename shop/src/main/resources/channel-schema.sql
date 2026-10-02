-- Tiangge channel tables (Lab 4)
-- Run this in the Supabase SQL Editor (Dashboard > SQL Editor) BEFORE starting
-- the application. It is safe to run more than once.
--
-- These three tables are the only durable state the channel needs: where the
-- feed was read up to, which events were already handled, and what this
-- application decided about each marketplace order.

-- 1. Where the order feed was last read. Row id is always 1.
CREATE TABLE IF NOT EXISTS channel_cursor (
    id        INTEGER PRIMARY KEY,
    last_seq  BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT channel_cursor_singleton CHECK (id = 1)
);

INSERT INTO channel_cursor (id, last_seq)
VALUES (1, 0)
ON CONFLICT (id) DO NOTHING;

-- 2. Every feed event already handled. The primary key is what makes a
--    redelivered event a no-op instead of a second order.
CREATE TABLE IF NOT EXISTS channel_processed_event (
    event_id     VARCHAR(120) PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 3. One row per marketplace order: what it became, and which message still
--    owes Tiangge an answer.
CREATE TABLE IF NOT EXISTS channel_order_link (
    tiangge_order_id VARCHAR(120) PRIMARY KEY,
    shop_order_id    BIGINT,
    state            VARCHAR(24) NOT NULL,
    lines            TEXT NOT NULL,
    placed_at        TIMESTAMPTZ,
    outbox           VARCHAR(20),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT channel_order_link_state
        CHECK (state IN ('ACCEPTED', 'REJECTED', 'BACKORDERED', 'CANCELLED', 'CANCELLED_BY_CUSTOMER')),
    CONSTRAINT channel_order_link_outbox
        CHECK (outbox IS NULL OR outbox IN ('DECISION', 'RESOLUTION', 'CANCEL_CONFIRM'))
);

-- The outbox sweeper walks this every two seconds.
CREATE INDEX IF NOT EXISTS ix_channel_order_link_outbox
    ON channel_order_link (outbox)
    WHERE outbox IS NOT NULL;

-- The backorder sweep walks these oldest first.
CREATE INDEX IF NOT EXISTS ix_channel_order_link_backorder
    ON channel_order_link (placed_at)
    WHERE state = 'BACKORDERED';