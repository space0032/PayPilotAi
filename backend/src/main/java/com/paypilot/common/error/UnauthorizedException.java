package com.paypilot.common.error;

import org.springframework.http.HttpStatus;

/** 401 with a stable machine code - for requests that fail authentication
 * outside the standard security filter path (e.g. webhook signatures). */
public class UnauthorizedException extends ApiException {

    public UnauthorizedException(String code, String message) {
        super(HttpStatus.UNAUTHORIZED, code, message);
    }
}
