package com.acme.yapi.core.route;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Log policy of a route. {@code password} is always masked; {@code maskFields} adds to it. {@code maxBodyBytes}
 * limits only the logged backend response; the request body is logged untrimmed for the legacy Elastic documents.
 */
public record LoggingSpec(boolean logRequestBody, boolean logResponseBody, List<String> maskFields,
                          int maxBodyBytes, String binaryInfo) {

    public static final String ALWAYS_MASKED = "password";
    public static final int DEFAULT_MAX_BODY_BYTES = 65_536;
    public static final int MAX_BODY_BYTES_LIMIT = 262_144;

    public static LoggingSpec defaults() {
        return new LoggingSpec(true, false, List.of(), DEFAULT_MAX_BODY_BYTES, null);
    }

    public Set<String> effectiveMaskFields() {
        Set<String> fields = new LinkedHashSet<>();
        fields.add(ALWAYS_MASKED);
        fields.addAll(maskFields);
        return fields;
    }
}
