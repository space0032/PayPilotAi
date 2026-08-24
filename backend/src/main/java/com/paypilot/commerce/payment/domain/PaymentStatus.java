package com.paypilot.commerce.payment.domain;

/**
 * Payment FSM, values mirrored 1:1 by the DB CHECK constraint from V2
 * (widened by V7 for refunds). Transitions only ever happen through
 * {@link #canTransitionTo} so webhook handlers cannot invent paths.
 *
 * CREATED -> PAYMENT_PENDING -> AUTHORIZED -> PROCESSING -> SUCCESS -> REFUNDED
 *      \            \               \
 *       -> FAILED / EXPIRED / CANCELLED at every pre-SUCCESS stage
 */
public enum PaymentStatus {

    CREATED,
    PAYMENT_PENDING,
    AUTHORIZED,
    PROCESSING,
    SUCCESS,
    REFUNDED,
    FAILED,
    EXPIRED,
    CANCELLED;

    public boolean canTransitionTo(PaymentStatus target) {
        return switch (this) {
            case CREATED -> target == PAYMENT_PENDING || target == FAILED
                    || target == EXPIRED || target == CANCELLED;
            case PAYMENT_PENDING -> target == AUTHORIZED || target == FAILED
                    || target == EXPIRED || target == CANCELLED;
            case AUTHORIZED -> target == PROCESSING || target == FAILED
                    || target == CANCELLED;
            case PROCESSING -> target == SUCCESS || target == FAILED;
            case SUCCESS -> target == REFUNDED;
            default -> false; // terminal states: REFUNDED, FAILED, EXPIRED, CANCELLED
        };
    }

    /** An attempt still holding the order open (a retry may follow). */
    public boolean isActive() {
        return this == CREATED || this == PAYMENT_PENDING
                || this == AUTHORIZED || this == PROCESSING;
    }
}
