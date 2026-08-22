-- =============================================================================
-- PayPilot AI — applied offer on active carts (Phase 6)
--
-- A cart carries at most one applied offer; checkout copies it onto the order.
-- =============================================================================

ALTER TABLE carts
    ADD COLUMN applied_offer_id BIGINT REFERENCES offers(id);
