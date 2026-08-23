package com.paypilot.agent;

import java.util.List;

/**
 * Turns a natural-language goal into an ordered list of tool calls.
 * The live LLM implementation arrives in a later phase; the port exists
 * now so orchestration, auditing, and budget enforcement are built and
 * tested against the deterministic mock exactly as they will run in prod.
 */
public interface AgentPlanner {

    /**
     * @param sessionId owning session (for ownership checks inside tools)
     * @param userId    authenticated principal the tools act as
     * @param goal      raw user goal text
     */
    List<AgentStep> plan(Long sessionId, Long userId, String goal);
}
