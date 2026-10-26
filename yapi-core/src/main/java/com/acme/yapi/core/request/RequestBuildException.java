package com.acme.yapi.core.request;

import com.acme.yapi.core.validation.FieldError;

/** The body passed validation but a value needed by the backend request could not be produced. */
public class RequestBuildException extends RuntimeException {

    private final transient FieldError error;

    public RequestBuildException(String field, String message) {
        super(message);
        this.error = new FieldError(field, message);
    }

    public FieldError error() {
        return error;
    }
}
