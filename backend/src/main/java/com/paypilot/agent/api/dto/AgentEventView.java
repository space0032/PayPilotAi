package com.paypilot.agent.api.dto;

import java.util.Map;

/** One audited tool call in a session transcript. */
public record AgentEventView(
        int seq,
        String tool,
        Map<String, Object> arguments,
        Map<String, Object> result,
        boolean success,
        String errorCode) {
}
