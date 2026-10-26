package com.acme.yapi.admin.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;

import com.acme.yapi.core.validation.FieldError;

/** An error answered as a problem detail: {@code code} for the screen, {@code detail} in Turkish for the user. */
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

    public static ApiException validation(String what, List<FieldError> errors) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                what + " kaydedilemedi: " + errors.size() + " alan hatalı.", errors, Map.of());
    }

    public static ApiException validation(List<FieldError> errors) {
        return validation("İstek", errors);
    }

    public static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", detail);
    }

    public static ApiException forbidden(String detail) {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", detail);
    }

    public static ApiException conflict(String code, String detail) {
        return new ApiException(HttpStatus.CONFLICT, code, detail);
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
