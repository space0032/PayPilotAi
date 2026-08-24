-- Phase 14: refunds. SUCCESS becomes refundable - a terminal-but-reversible
-- state for money that came back. The CHECK constraint is rebuilt rather
-- than extended because PostgreSQL cannot ALTER an IN list in place.
ALTER TABLE payments DROP CONSTRAINT payments_status_check;

ALTER TABLE payments ADD CONSTRAINT payments_status_check
    CHECK (status IN (
        'CREATED','PAYMENT_PENDING','AUTHORIZED','PROCESSING',
        'SUCCESS','FAILED','EXPIRED','CANCELLED','REFUNDED'));

-- The gateway's own receipt for the returned money.
ALTER TABLE payments ADD COLUMN refund_id VARCHAR(64);

