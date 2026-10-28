package com.acme.yapi.core.route;

/**
 * The parsed {@code definition} JSON of a route, with defaults applied.
 *
 * @param alarm        the alarm check of the subtask; {@link AlarmSpec#inherit()} by default
 * @param alarmService    non-null when the route is an alarm service route
 * @param plugins         the {@code SUBTASK} plugins the subtask chooses, by name
 * @param disabledPlugins plugins it would inherit ({@code GLOBAL}, {@code PROJECT}) that it turns off, by name
 */
public record RouteDefinition(RequestSpec request, ResponseType responseType, int timeoutMs, int maxAttempts,
                              LoggingSpec logging, AlarmSpec alarm, AlarmServiceSpec alarmService,
                              java.util.List<String> plugins, java.util.List<String> disabledPlugins) {

    public boolean isAlarmService() {
        return alarmService != null;
    }

    public static final int DEFAULT_TIMEOUT_MS = 5_000;
    public static final int MIN_TIMEOUT_MS = 100;
    public static final int MAX_TIMEOUT_MS = 60_000;
    public static final int MAX_ATTEMPTS_LIMIT = 3;
}
