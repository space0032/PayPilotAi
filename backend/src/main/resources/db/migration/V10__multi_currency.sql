-- Phase 19: multi-currency support. Adds ISO 4217 currency codes to the
-- three monetary tables so every amount carries its unit.  Payments
-- already had currency; this extends the same contract to products and
-- orders.  All new columns default to 'INR' so every existing row is
-- correct without a data backfill.
--
-- Uses VARCHAR(3) to match the Payment entity convention (V6 changed
-- payments.currency from CHAR to VARCHAR to satisfy Hibernate validation).

ALTER TABLE products
    ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'INR';

ALTER TABLE orders
    ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'INR';

ALTER TABLE order_items
    ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'INR';

-- Sanity: reject obvious garbage at the DB level.
ALTER TABLE products    ADD CONSTRAINT chk_products_currency    CHECK (currency ~ '^[A-Z]{3}$');
ALTER TABLE orders      ADD CONSTRAINT chk_orders_currency      CHECK (currency ~ '^[A-Z]{3}$');
ALTER TABLE order_items ADD CONSTRAINT chk_order_items_currency CHECK (currency ~ '^[A-Z]{3}$');
