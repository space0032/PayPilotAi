package com.paypilot.common.error;

import org.springframework.http.HttpStatus;

/** Authentication failed: bad credentials or an invalid/expired token. */
public class UnauthorizedException extends ApiException {

    public UnauthorizedException(String code, String message) {
        super(HttpStatus.UNAUTHORIZED, code, message);
    }
}
