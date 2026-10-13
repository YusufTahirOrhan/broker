package com.acme.broker.core.route;

import java.util.Optional;

import tools.jackson.databind.JsonNode;

/** Type of a field the frontend sends in the request body. */
public enum FieldType {
    STRING("string"),
    INTEGER("integer"),
    NUMBER("number"),
    BOOLEAN("boolean"),
    OBJECT("object"),
    ARRAY("array");

    private final String jsonName;

    FieldType(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }

    public static Optional<FieldType> parse(String value) {
        for (FieldType type : values()) {
            if (type.jsonName.equals(value)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    /** Whether a non-null JSON value has this type. Booleans are never numbers. */
    public boolean matches(JsonNode value) {
        return switch (this) {
            case STRING -> value.isString();
            case INTEGER -> value.isIntegralNumber();
            case NUMBER -> value.isNumber();
            case BOOLEAN -> value.isBoolean();
            case OBJECT -> value.isObject();
            case ARRAY -> value.isArray();
        };
    }

    /** Type inferred from a sample value; null values fall back to string. */
    public static FieldType infer(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return STRING;
        }
        if (value.isBoolean()) {
            return BOOLEAN;
        }
        if (value.isIntegralNumber()) {
            return INTEGER;
        }
        if (value.isNumber()) {
            return NUMBER;
        }
        if (value.isObject()) {
            return OBJECT;
        }
        if (value.isArray()) {
            return ARRAY;
        }
        return STRING;
    }
}
