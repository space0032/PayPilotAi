package com.paypilot.agent.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Full transcript of an agent session: consent state, cumulative reserved
 * spend, every audited tool call in order, and the conversation log.
 */
public record SessionTranscriptResponse(
        Long sessionId,
        String title,
        String consentState,
        BigDecimal reservedSpend,
        List<AgentToolCallView> toolCalls,
        List<AgentMessageView> messages) {
}
