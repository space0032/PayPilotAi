package com.paypilot.agent.api.dto;

import java.util.Map;

/** One audited tool call: the unit of agent accountability. */
public record AgentToolCallView(
        String tool,
        Map<String, Object> arguments,
        Map<String, Object> resultSummary,
        String status,
        String error,
        String correlationId) {
}
