package com.acme.broker.core.route;

/** The parsed {@code definition} JSON of a route, with defaults applied. */
public record RouteDefinition(RequestSpec request, ResponseType responseType, int timeoutMs, int maxAttempts,
                              LoggingSpec logging) {

    public static final int DEFAULT_TIMEOUT_MS = 5_000;
    public static final int MIN_TIMEOUT_MS = 100;
    public static final int MAX_TIMEOUT_MS = 60_000;
    public static final int MAX_ATTEMPTS_LIMIT = 3;
}
