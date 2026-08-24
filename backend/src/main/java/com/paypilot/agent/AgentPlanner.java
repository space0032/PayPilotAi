package com.paypilot.agent;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Produces the next tool call for an agent session, one decision at a
 * time. Planning is conversational: each decision sees everything that
 * happened before it (prior tool results via ctx, the immediately
 * preceding outcome explicitly), which is what makes resumable sessions
 * possible - a paused run rebuilds its PlanSession from the persisted
 * trace and continues mid-journey.
 *
 * Two implementations exist behind paypilot.agent.planner:
 *   mock - deterministic scripted journey (default, no account needed)
 *   live - LLM-driven via {@link LlmPort} (openai-compatible provider)
 * Both are bound by identical guardrails in AgentTools; a planner can
 * only ASK, never spend.
 */
public interface AgentPlanner {

    /**
     * Starts (or resumes) planning for one run over a session.
     *
     * @param sessionId owning session id
     * @param userId    authenticated principal the tools act as
     * @param title     session goal/title text
     * @param history   previously audited tool calls (name/status/error/
     *                  result), empty on a fresh session
     */
    PlanSession begin(Long sessionId, Long userId, String title,
                      List<Map<String, Object>> history);

    /** What the harness records after executing one planned step. */
    record StepOutcome(String tool, boolean success, String errorCode,
                       Map<String, Object> result) {
    }

    /** Yields decisions until the journey completes or pauses for a human. */
    interface PlanSession {
        /**
         * @param ctx  mutable map of prior step outputs (tool name -> result)
         * @param last outcome of the previous step, null before the first
         * @return next step, or empty to finish the run cleanly
         */
        Optional<AgentStep> next(Map<String, Object> ctx, StepOutcome last);
    }
}
