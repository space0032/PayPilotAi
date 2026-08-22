package com.paypilot.common.error;

import org.springframework.http.HttpStatus;

/**
 * Base class for all intentional, user-facing API failures.
 *
 * Rationale: Spring can map unexpected throwables to a generic 500, but
 * business failures need a stable machine-readable code, an HTTP status,
 * and a human-safe message. Extending this type is the ONLY sanctioned way
 * for domain code to abort a request with an error response.
 */
public abstract class ApiException extends RuntimeException {

    private final transient HttpStatus status;
    private final String code;

    protected ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
