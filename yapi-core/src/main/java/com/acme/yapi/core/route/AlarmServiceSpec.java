package com.acme.yapi.core.route;

/**
 * The definition's {@code alarmService}: the route is an alarm service, called by the broker with the request's
 * values ({@code ${alarm.values}}) and never by frontends. Its answer lists the values that raised an alarm.
 *
 * @param resultPath dotted path of that list in the answer ({@code data.alarms}); null when the answer itself is it
 */
public record AlarmServiceSpec(String resultPath) {
}
