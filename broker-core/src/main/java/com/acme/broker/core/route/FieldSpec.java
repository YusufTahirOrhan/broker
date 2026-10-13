package com.acme.broker.core.route;

import tools.jackson.databind.JsonNode;

/**
 * A field of the frontend body. {@code name} is a dotted path ({@code adres.il}); {@code defaultValue} is null
 * when the definition gives no default.
 */
public record FieldSpec(String name, FieldType type, boolean required, JsonNode defaultValue) {

    public boolean hasDefault() {
        return defaultValue != null && !defaultValue.isNull() && !defaultValue.isMissingNode();
    }

    /** Fields that always have a value once validation passed: required ones and those with a default. */
    public boolean alwaysPresent() {
        return required || hasDefault();
    }
}
