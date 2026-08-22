-- Phase 8 activates the payments skeleton declared in V2 (FSM columns,
-- razorpay_* correlation keys, payment_events ledger were already in place).
-- Only ownership and lookup indexes were missing. Safe NOT NULL: the table
-- is empty in every environment until this phase ships.
ALTER TABLE payments ADD COLUMN user_id BIGINT NOT NULL REFERENCES users(id);

-- CHAR(3) fights Hibernate 6 schema validation (bpchar vs varchar); ISO
-- currency codes need no blank padding, so normalize to VARCHAR(3).
ALTER TABLE payments ALTER COLUMN currency TYPE VARCHAR(3);

CREATE INDEX idx_payments_order ON payments(order_id);
CREATE INDEX idx_payments_user ON payments(user_id);
