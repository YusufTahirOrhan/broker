package com.acme.yapi.core.request;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/**
 * What the request-wide placeholders read ({@code ${now}}, {@code ${traceId}}, {@code ${route.subtask}},
 * {@code ${client.ip}}, {@code ${user.permissions}} and the like); see
 * {@link com.acme.yapi.core.template.ContextPlaceholders}.
 *
 * @param routeVersion    the serving version of the route; null in previews of an unsaved definition
 * @param userPermissions the verified user's permissions; empty without a session
 * @param zone            the time zone of {@code ${now}} and {@code ${today}}
 * @param clock           the time source; {@code ${now}} is read when the value is written
 */
public record RequestScope(String environment, String traceId, String maintask, String subtask, Integer routeVersion,
                           String clientIp, String userAgent, List<String> userPermissions, Instant receivedAt,
                           ZoneId zone, Clock clock) {

    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Europe/Istanbul");

    public RequestScope {
        userPermissions = userPermissions == null ? List.of() : List.copyOf(userPermissions);
        zone = zone == null ? DEFAULT_ZONE : zone;
        clock = clock == null ? Clock.systemUTC() : clock;
    }
}
