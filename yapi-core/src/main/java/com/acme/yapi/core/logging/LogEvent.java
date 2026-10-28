package com.acme.yapi.core.logging;

import java.time.Instant;
import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * One of the two events every request produces. Field names on the wire are snake_case (see {@link EventCodec}).
 *
 * <ul>
 *   <li>{@code maintask}, {@code subtask}: the registered spelling; the spelling the frontend sent stays in the
 *       REQUEST body and goes to the legacy Elastic document.</li>
 *   <li>{@code uid} is what the frontend put in the body, {@code userId} the verified session user.</li>
 *   <li>{@code queryId}, {@code permList}, {@code token} exist for the legacy Elastic documents.</li>
 *   <li>{@code body}: REQUEST carries the masked frontend body, untrimmed; RESPONSE carries the masked backend
 *       body only when the route logs it, trimmed to its limit.</li>
 *   <li>{@code clientResponse}: what the frontend received, with its top-level {@code data} emptied.</li>
 *   <li>{@code alarm}: RESPONSE only, when an alarm check ran: {@code {"maintask", "subtask"}} of the alarm service
 *       route and either {@code alarms} (the values that raised one) with {@code params} (the masked request body)
 *       or {@code error} when the check could not be made.</li>
 *   <li>{@code denial}: RESPONSE only, for a {@code DENIED} request ({@link Denial}).</li>
 * </ul>
 */
public record LogEvent(
        String requestId,
        String traceId,
        EventType eventType,
        Instant eventTime,
        String environment,
        String maintask,
        String subtask,
        Integer routeVersion,
        String responseProfile,
        Integer responseProfileVersion,
        String application,
        String uid,
        String userId,
        String queryId,
        List<String> permList,
        String token,
        String sessionOperation,
        String clientIp,
        String userAgent,
        String httpMethod,
        String targetPath,
        Integer statusCode,
        String outcome,
        Long durationMs,
        JsonNode body,
        Integer bodySize,
        Boolean bodyTruncated,
        JsonNode clientResponse,
        String errorMessage,
        String yapiInstance,
        JsonNode alarm,
        Denial denial) {

    public enum EventType {
        REQUEST, RESPONSE
    }

    /** Why the broker refused a request with {@code DENIED}. */
    public enum DeniedReason {
        PERMISSION, APPLICATION, SESSION
    }

    /**
     * RESPONSE only, for a {@code DENIED} request. On the wire its parts are top-level fields.
     *
     * @param routePermissions     what the route asks for ({@code permissions}, then {@code foreign_permissions});
     *                             null when no route was found
     * @param hasProjectPermission whether one of the user's permissions starts with the route's project prefix and
     *                             {@code _}; null without a route, a verified user or a known prefix
     * @param requestBody          the frontend body as the REQUEST event logs it (masked), so the refusal is
     *                             readable on its own
     */
    public record Denial(DeniedReason reason, List<String> routePermissions, Boolean hasProjectPermission,
                         JsonNode requestBody) {
    }
}
