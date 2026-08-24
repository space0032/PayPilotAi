package com.paypilot.commerce.payment.gateway;

/**
 * Raised when the payment gateway cannot be reached or refuses a
 * request. Transport/5xx map to DOWN (retry may help); 4xx maps to
 * REJECTED (retrying the same call will not). Callers translate this
 * into a client-facing error without leaking gateway internals.
 */
public class GatewayException extends RuntimeException {

    public enum Kind { DOWN, REJECTED }

    private final Kind kind;

    public GatewayException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public GatewayException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
