-- Supabase setup script for IT342 Modular Monolith (Lab 2)
-- Run this in the Supabase SQL Editor (Dashboard > SQL Editor)
-- This script recreates the schema from scratch, including seed data.

-- Drop tables in dependency order
DROP TABLE IF EXISTS order_items;
DROP TABLE IF EXISTS notifications;
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS inventory;

-- Inventory table
CREATE TABLE IF NOT EXISTS inventory (
    product_id  VARCHAR(10) PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    stock       INTEGER NOT NULL DEFAULT 0
);

-- Orders table (status supports CONFIRMED / REJECTED / CANCELLED)
CREATE TABLE IF NOT EXISTS orders (
    order_id    BIGSERIAL PRIMARY KEY,
    status      VARCHAR(20) NOT NULL,
    reason      VARCHAR(255),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Order line items
CREATE TABLE IF NOT EXISTS order_items (
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT NOT NULL REFERENCES orders(order_id) ON DELETE CASCADE,
    product_id  VARCHAR(10) NOT NULL REFERENCES inventory(product_id),
    quantity    INTEGER NOT NULL
);

-- Notifications (activity feed from domain events)
CREATE TABLE IF NOT EXISTS notifications (
    notification_id  BIGSERIAL PRIMARY KEY,
    message          VARCHAR(255) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Seed data
INSERT INTO inventory (product_id, name, stock) VALUES
    ('P100', 'Wireless Mouse',        25),
    ('P200', 'Mechanical Keyboard',   10),
    ('P300', 'USB-C Hub',              0)
ON CONFLICT (product_id) DO NOTHING;