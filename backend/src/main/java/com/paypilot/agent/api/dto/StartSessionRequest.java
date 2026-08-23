package com.paypilot.agent.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Start an autonomous session. The goal doubles as the session title;
 * spend safety comes from the consent ladder plus the configured
 * per-purchase cap, not from client-supplied numbers.
 */
public record StartSessionRequest(
        @NotBlank @Size(max = 200) String goal) {
}
