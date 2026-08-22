package com.paypilot.common.error;

import org.springframework.http.HttpStatus;

/**
 * The request collides with current system state: duplicate creation,
 * illegal state transition, concurrent modification, etc.
 */
public class ConflictException extends ApiException {

    public ConflictException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
