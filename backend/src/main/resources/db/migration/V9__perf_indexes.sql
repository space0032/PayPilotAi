-- Phase 18: targeted indexes for the hot paths that showed up in load
-- tests. Each one closes a sequential-scan gap on a query that runs on
-- every payment sweep, every agent resume, and every daily-cap check.
--
-- Expiry sweeper: WHERE status IN (...) AND expires_at < now()
-- A partial index keeps it small: only rows that actually need sweeping.
CREATE INDEX idx_payments_active_expiry
    ON payments (expires_at)
    WHERE status IN ('CREATED','PAYMENT_PENDING','AUTHORIZED','PROCESSING');

-- Transcript read path: session + created_at + id for stable ordering.
-- The existing (session_id, created_at) index is close but misses the
-- id tiebreaker, forcing a sort on every agent resume.
CREATE INDEX idx_agent_tool_calls_session_time_id
    ON agent_tool_calls (session_id, created_at, id);

CREATE INDEX idx_agent_messages_session_time_id
    ON agent_messages (session_id, created_at, id);

-- Daily spend cap: user + type + created_at for the rolling-24h SUM.
CREATE INDEX idx_customer_events_user_type_time
    ON customer_events (user_id, type, created_at);

-- Reserved-spend per session: session + type for the consent approval path.
CREATE INDEX idx_customer_events_session_type
    ON customer_events (session_id, type);
