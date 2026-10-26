package com.acme.yapi.core.route;

/** An alarm service route by name: a project's {@code alarmRoute} in an environment. */
public record AlarmRoute(String maintask, String subtask) {
}
