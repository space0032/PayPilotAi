package com.paypilot.agent.domain;

/**
 * Outcome of one agent tool call (V2 schema CHECK). REJECTED is reserved
 * for guardrail refusals (consent missing, spend cap) as opposed to
 * ordinary domain errors.
 */
public enum ToolCallStatus {
    OK,
    ERROR,
    REJECTED
}
