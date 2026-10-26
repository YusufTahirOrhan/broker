package com.acme.yapi.core.route;

/**
 * The definition's {@code alarm}: which alarm check a subtask gets before its service is called. {@code INHERIT}
 * (the default) uses the project's {@code alarmRoute} in the environment, {@code OFF} none, {@code ROUTE} the given
 * alarm service route.
 */
public record AlarmSpec(Check check, String maintask, String subtask) {

    public enum Check {
        INHERIT, OFF, ROUTE
    }

    public static AlarmSpec inherit() {
        return new AlarmSpec(Check.INHERIT, null, null);
    }
}
