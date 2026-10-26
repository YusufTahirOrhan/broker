package com.acme.yapi.envadmin.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;

import com.acme.yapi.core.validation.FieldError;

/** An error answered as a problem detail with a machine-readable {@code code} and a Turkish {@code detail}. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final transient List<FieldError> errors;
    private final transient Map<String, Object> properties;

    public ApiException(HttpStatus status, String code, String detail) {
        this(status, code, detail, List.of(), Map.of());
    }

    public ApiException(HttpStatus status, String code, String detail, List<FieldError> errors,
                        Map<String, Object> properties) {
        super(detail);
        this.status = status;
        this.code = code;
        this.errors = List.copyOf(errors);
        this.properties = Map.copyOf(properties);
    }

    public static ApiException validation(List<FieldError> errors) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "İstek kaydedilemedi: " + errors.size() + " alan hatalı.", errors, Map.of());
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<FieldError> errors() {
        return errors;
    }

    public Map<String, Object> properties() {
        return properties;
    }
}
