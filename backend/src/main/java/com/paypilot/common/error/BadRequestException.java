package com.paypilot.common.error;

import org.springframework.http.HttpStatus;

/**
 * 400 with a stable machine code - for rejected query parameters and other
 * structurally-valid HTTP but semantically-invalid requests.
 */
public class BadRequestException extends ApiException {

    public BadRequestException(String code, String message) {
        super(HttpStatus.BAD_REQUEST, code, message);
    }
}
