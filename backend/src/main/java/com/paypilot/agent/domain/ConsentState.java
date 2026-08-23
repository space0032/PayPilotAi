package com.paypilot.agent.domain;

/**
 * Purchase-authorization gate over agent spending (V2 schema CHECK).
 * The agent may browse and cart freely, but money only moves after the
 * human walks this ladder: NONE -> REQUESTED -> CONFIRMED -> CONSUMED.
 */
public enum ConsentState {
    NONE,
    REQUESTED,
    CONFIRMED,
    CONSUMED,
    EXPIRED,
    CANCELLED;

    /** Legal transitions mirror the guardrail design; terminal states end it. */
    public boolean canTransitionTo(ConsentState target) {
        return switch (this) {
            case NONE -> target == REQUESTED || target == CANCELLED;
            case REQUESTED -> target == CONFIRMED || target == EXPIRED
                    || target == CANCELLED;
            case CONFIRMED -> target == CONSUMED || target == CANCELLED;
            default -> false; // CONSUMED, EXPIRED, CANCELLED are final
        };
    }
}
