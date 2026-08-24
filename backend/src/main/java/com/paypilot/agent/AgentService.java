package com.paypilot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypilot.agent.api.dto.AgentMessageView;
import com.paypilot.agent.api.dto.AgentToolCallView;
import com.paypilot.agent.api.dto.SessionTranscriptResponse;
import com.paypilot.agent.api.dto.StartSessionRequest;
import com.paypilot.agent.domain.AgentMessage;
import com.paypilot.agent.domain.AgentMessageRole;
import com.paypilot.agent.domain.AgentSession;
import com.paypilot.agent.domain.AgentToolCall;
import com.paypilot.agent.domain.ConsentState;
import com.paypilot.agent.repo.AgentMessageRepository;
import com.paypilot.agent.repo.AgentSessionRepository;
import com.paypilot.agent.repo.AgentToolCallRepository;
import com.paypilot.agent.repo.CustomerEventRepository;
import com.paypilot.common.error.ApiException;
import com.paypilot.common.error.ConflictException;
import com.paypilot.common.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Runs a planner's decisions under the safety harness: every tool call
 * is audited with arguments, summarized result, outcome and duration.
 * A domain failure (or guardrail refusal) ends the journey as DATA - a
 * transcript whose last call explains exactly why - never as an HTTP 500.
 *
 * Deliberately NOT one big transaction: each audit row commits on its
 * own, so the trace survives crashes mid-journey and resume can rebuild
 * from it. Resume is what makes the human-in-the-loop work: run 1 ends
 * at REQUESTED consent, the human answers via the session API, run 2
 * continues from the same trace.
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    /** Error codes that represent the guardrails refusing, not failing. */
    private static final Set<String> REJECTIONS = Set.of(
            "SPEND_CAP_EXCEEDED", "PURCHASE_CONSENT_REQUIRED",
            "DAILY_SPEND_CAP_EXCEEDED");

    /** Hard ceiling on decisions per run; runaway planners stop here. */
    private static final int MAX_STEPS_PER_RUN = 20;

    private final AgentSessionRepository sessions;
    private final AgentToolCallRepository toolCalls;
    private final AgentMessageRepository messages;
    private final CustomerEventRepository events;
    private final AgentPlanner planner;
    private final AgentTools tools;
    private final ObjectMapper objectMapper;

    public AgentService(AgentSessionRepository sessions,
                        AgentToolCallRepository toolCalls,
                        AgentMessageRepository messages,
                        CustomerEventRepository events,
                        AgentPlanner planner,
                        AgentTools tools,
                        ObjectMapper objectMapper) {
        this.sessions = sessions;
        this.toolCalls = toolCalls;
        this.messages = messages;
        this.events = events;
        this.planner = planner;
        this.tools = tools;
        this.objectMapper = objectMapper;
    }

    public SessionTranscriptResponse startAndRun(Long userId, StartSessionRequest request) {
        String title = request.goal().trim();
        AgentSession session = sessions.save(new AgentSession(userId, title));
        messages.save(new AgentMessage(session.getId(),
                AgentMessageRole.USER, title));

        AgentPlanner.PlanSession planSession =
                planner.begin(session.getId(), userId, title, List.of());
        return runLoop(userId, session.getId(), planSession,
                new LinkedHashMap<>(), null);
    }

    /**
     * Continues a paused session (typically: consent was just answered,
     * or the gateway callback landed between runs). Rebuilds both context
     * and planner history from the persisted audit. No consent-state gate
     * here on purpose: pausing AFTER initiation is legal (consent sits at
     * CONSUMED while capture is pending), and double-spending is already
     * impossible - pay() refuses anything but CONFIRMED.
     */
    public SessionTranscriptResponse run(Long userId, Long sessionId) {
        AgentSession session = owned(userId, sessionId);
        List<Map<String, Object>> history =
                toolCalls.findBySessionIdOrderByCreatedAtAscIdAsc(sessionId)
                        .stream()
                        .<Map<String, Object>>map(c -> {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("tool", c.getTool());
                            row.put("status", c.getStatus().name());
                            if (c.getResultSummary() != null) {
                                row.put("result", c.getResultSummary());
                            }
                            if (c.getError() != null) {
                                row.put("error", c.getError());
                            }
                            return row;
                        })
                        .toList();
        LinkedHashMap<String, Object> ctx = new LinkedHashMap<>();
        toolCalls.findBySessionIdOrderByCreatedAtAscIdAsc(sessionId).stream()
                .filter(c -> c.getStatus().name().equals("OK"))
                .forEach(c -> ctx.put(c.getTool(), c.getResultSummary()));

        AgentPlanner.PlanSession planSession =
                planner.begin(sessionId, userId, session.getTitle(), history);
        return runLoop(userId, sessionId, planSession, ctx, null);
    }

    /** The human says yes. Only valid while consent sits at REQUESTED. */
    public SessionTranscriptResponse confirmConsent(Long userId, Long sessionId) {
        AgentSession session = owned(userId, sessionId);
        if (session.getConsentState() != ConsentState.REQUESTED) {
            throw new ConflictException("CONSENT_NOT_REQUESTED",
                    ("Session %d is %s; only a REQUESTED purchase can be "
                            + "approved").formatted(sessionId,
                            session.getConsentState()));
        }
        session.confirmConsent();
        sessions.save(session);
        messages.save(new AgentMessage(sessionId, AgentMessageRole.SYSTEM,
                "User approved the purchase."));
        return transcript(userId, sessionId);
    }

    /** The human says no (or changes their mind before payment starts). */
    public SessionTranscriptResponse cancelConsent(Long userId, Long sessionId) {
        AgentSession session = owned(userId, sessionId);
        try {
            session.cancelConsent();
            sessions.save(session);
        } catch (IllegalStateException e) {
            throw new ConflictException("CONSENT_INVALID_TRANSITION",
                    ("Session %d is %s and cannot be declined"
                            .formatted(sessionId, session.getConsentState())));
        }
        messages.save(new AgentMessage(sessionId, AgentMessageRole.SYSTEM,
                "User declined the purchase."));
        return transcript(userId, sessionId);
    }

    public SessionTranscriptResponse get(Long userId, Long sessionId) {
        return transcript(userId, sessionId);
    }

    // ------------------------------------------------------------------

    private SessionTranscriptResponse runLoop(Long userId, Long sessionId,
                                              AgentPlanner.PlanSession planSession,
                                              LinkedHashMap<String, Object> ctx,
                                              AgentPlanner.StepOutcome last) {
        for (int executed = 0; executed < MAX_STEPS_PER_RUN; executed++) {
            Optional<AgentStep> next;
            try {
                next = planSession.next(ctx, last);
            } catch (Exception e) {
                log.error("Agent session {} planner failed to decide",
                        sessionId, e);
                auditPlanFailure(sessionId,
                        e instanceof ApiException ae ? ae.code() : "PLANNER_ERROR");
                return transcript(userId, sessionId);
            }
            if (next.isEmpty()) {
                break;
            }
            AgentStep step = next.get();
            long startNanos = System.nanoTime();
            var call = new AgentToolCall(sessionId, step.tool(), step.arguments());
            try {
                Object out = step.action().execute(tools, ctx);
                int durationMs = elapsedMs(startNanos);
                call.succeed(toJsonMap(out), durationMs);
                toolCalls.save(call);
                ctx.put(step.tool(), out);
                last = new AgentPlanner.StepOutcome(step.tool(), true, null,
                        toJsonMap(out));
            } catch (ApiException e) {
                int durationMs = elapsedMs(startNanos);
                boolean rejected = REJECTIONS.contains(e.code());
                call.fail(e.code(), durationMs, rejected);
                toolCalls.save(call);
                last = new AgentPlanner.StepOutcome(step.tool(), false,
                        e.code(), null);
                // A refusal is information, not termination: let the
                // planner react (e.g. ask consent after a cap refusal is
                // pointless, but a model may choose done itself).
                if (rejected) {
                    break;
                }
            } catch (Exception e) {
                log.error("Agent session {} tool {} crashed",
                        sessionId, step.tool(), e);
                int durationMs = elapsedMs(startNanos);
                call.fail("INTERNAL_ERROR", durationMs, false);
                toolCalls.save(call);
                last = new AgentPlanner.StepOutcome(step.tool(), false,
                        "INTERNAL_ERROR", null);
                // Unexpected crash: stop feeding the planner a broken world.
                break;
            }
        }
        return transcript(userId, sessionId);
    }

    private void auditPlanFailure(Long sessionId, String code) {
        var call = new AgentToolCall(sessionId, "plan_next_step", Map.of());
        call.fail(code, 0, false);
        toolCalls.save(call);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toJsonMap(Object out) {
        if (out == null) {
            return null;
        }
        if (out instanceof Map<?, ?> m) {
            return new LinkedHashMap<>((Map<String, Object>) m);
        }
        return objectMapper.convertValue(out, LinkedHashMap.class);
    }

    private static int elapsedMs(long startNanos) {
        return (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startNanos) / 1_000_000);
    }

    private AgentSession owned(Long userId, Long sessionId) {
        return sessions.findById(sessionId)
                .filter(s -> s.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundException("Agent session", sessionId));
    }

    private SessionTranscriptResponse transcript(Long userId, Long sessionId) {
        AgentSession session = owned(userId, sessionId);
        List<AgentToolCallView> calls =
                toolCalls.findBySessionIdOrderByCreatedAtAscIdAsc(sessionId).stream()
                        .map(c -> new AgentToolCallView(c.getTool(), c.getArguments(),
                                c.getResultSummary(), c.getStatus().name(), c.getError()))
                        .toList();
        List<AgentMessageView> chat =
                messages.findBySessionIdOrderByCreatedAtAscIdAsc(sessionId).stream()
                        .map(m -> new AgentMessageView(m.getRole().name(), m.getContent()))
                        .toList();
        BigDecimal reservedSpend = BigDecimal.valueOf(
                events.sumReservedSpend(sessionId), 2);
        return new SessionTranscriptResponse(
                session.getId(),
                session.getTitle(),
                session.getConsentState().name(),
                reservedSpend,
                calls,
                chat);
    }
}
