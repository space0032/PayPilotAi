package com.paypilot.common.error;

import org.springframework.http.HttpStatus;

/** Requested resource does not exist or is not visible to the caller. */
public class NotFoundException extends ApiException {

    public NotFoundException(String resource, Object id) {
        super(HttpStatus.NOT_FOUND, "NOT_FOUND", resource + " not found: " + id);
    }
}
