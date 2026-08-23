package com.paypilot.agent.api.dto;

/** One chat-log line: who said what during the session. */
public record AgentMessageView(
        String role,
        String content) {
}
