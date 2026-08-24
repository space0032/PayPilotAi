-- Phase 15 (observability): thread the request correlation id into the
-- audited tool-call trace. A transcript row that cannot be tied back to
-- the log lines of the request that produced it is only half evidence.
ALTER TABLE agent_tool_calls ADD COLUMN correlation_id VARCHAR(64);
