package com.acme.broker.core.route;

import java.util.Locale;
import java.util.Optional;

public enum HttpMethodName {
    GET, POST, PUT, PATCH, DELETE;

    public static Optional<HttpMethodName> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (HttpMethodName method : values()) {
            if (method.name().equals(value.toUpperCase(Locale.ROOT))) {
                return Optional.of(method);
            }
        }
        return Optional.empty();
    }

    /** GET and DELETE never carry a body in a route definition. */
    public boolean allowsBody() {
        return this != GET && this != DELETE;
    }
}
