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
import com.paypilot.agent.repo.AgentMessageRepository;
import com.paypilot.agent.repo.AgentSessionRepository;
import com.paypilot.agent.repo.AgentToolCallRepository;
import com.paypilot.agent.repo.CustomerEventRepository;
import com.paypilot.common.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs a planner-produced step list under the safety harness: every tool
 * call is audited with arguments, summarized result, outcome and duration.
 * A domain failure (or guardrail refusal) ends the journey as DATA - a
 * transcript whose last call explains exactly why - never as an HTTP 500.
 *
 * Deliberately NOT one big transaction: each audit row commits on its own,
 * so the trace survives crashes mid-journey and reflects exactly how far
 * the agent got.
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    /** Error codes that represent the guardrails refusing, not failing. */
    private static final Set<String> REJECTIONS = Set.of(
            "SPEND_CAP_EXCEEDED", "PURCHASE_CONSENT_REQUIRED");

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
                AgentMessageRole.USER, request.goal().trim()));

        List<AgentStep> plan = planner.plan(session.getId(), userId, title);
        log.info("Agent session {} planned {} steps", session.getId(), plan.size());

        var ctx = new java.util.LinkedHashMap<String, Object>();
        for (AgentStep step : plan) {
            long startNanos = System.nanoTime();
            var call = new AgentToolCall(session.getId(), step.tool(), step.arguments());
            try {
                Object out = step.action().execute(tools, ctx);
                int durationMs = elapsedMs(startNanos);
                call.succeed(toJsonMap(out), durationMs);
                toolCalls.save(call);
                ctx.put(step.tool(), out);
            } catch (ApiException e) {
                int durationMs = elapsedMs(startNanos);
                boolean rejected = REJECTIONS.contains(e.code());
                call.fail(e.code(), durationMs, rejected);
                toolCalls.save(call);
                return transcript(userId, session.getId());
            } catch (Exception e) {
                // Unexpected failures leave an audited trail too.
                log.error("Agent session {} tool {} crashed",
                        session.getId(), step.tool(), e);
                call.fail("INTERNAL_ERROR", elapsedMs(startNanos), false);
                toolCalls.save(call);
                return transcript(userId, session.getId());
            }
        }
        return transcript(userId, session.getId());
    }

    public SessionTranscriptResponse get(Long userId, Long sessionId) {
        return transcript(userId, sessionId);
    }

    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> toJsonMap(Object out) {
        if (out == null) {
            return null;
        }
        if (out instanceof Map<?, ?> m) {
            return new java.util.LinkedHashMap<>((Map<String, Object>) m);
        }
        return objectMapper.convertValue(out, java.util.LinkedHashMap.class);
    }

    private static int elapsedMs(long startNanos) {
        return (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startNanos) / 1_000_000);
    }

    private SessionTranscriptResponse transcript(Long userId, Long sessionId) {
        AgentSession session = sessions.findById(sessionId)
                .filter(s -> s.getUserId().equals(userId))
                .orElseThrow(() -> new com.paypilot.common.error.NotFoundException(
                        "Agent session", sessionId));
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
