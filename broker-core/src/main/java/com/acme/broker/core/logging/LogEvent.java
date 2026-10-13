package com.acme.broker.core.logging;

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
        String brokerInstance) {

    public enum EventType {
        REQUEST, RESPONSE
    }
}
