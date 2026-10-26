package com.acme.yapi.core.validation;

/**
 * A rule violation pointing at a field. {@code field} is a dotted path such as
 * {@code definition.request.pathVariables.id}; the frontend maps it back to the form control.
 */
public record FieldError(String field, String message) {

    public FieldError prefixed(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return this;
        }
        return new FieldError(field == null || field.isEmpty() ? prefix : prefix + "." + field, message);
    }
}
