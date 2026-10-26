package com.acme.yapi.core.route;

/**
 * The parsed {@code definition} JSON of a route, with defaults applied.
 *
 * @param alarm        the alarm check of the subtask; {@link AlarmSpec#inherit()} by default
 * @param alarmService non-null when the route is an alarm service route
 */
public record RouteDefinition(RequestSpec request, ResponseType responseType, int timeoutMs, int maxAttempts,
                              LoggingSpec logging, AlarmSpec alarm, AlarmServiceSpec alarmService) {

    public boolean isAlarmService() {
        return alarmService != null;
    }

    public static final int DEFAULT_TIMEOUT_MS = 5_000;
    public static final int MIN_TIMEOUT_MS = 100;
    public static final int MAX_TIMEOUT_MS = 60_000;
    public static final int MAX_ATTEMPTS_LIMIT = 3;
}
