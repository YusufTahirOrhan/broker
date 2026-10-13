package com.acme.broker.runtime.web;

import java.time.Instant;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.acme.broker.core.json.Json;
import com.acme.broker.core.logging.LogEvent;
import com.acme.broker.core.logging.Masker;
import com.acme.broker.core.response.Outcome;
import com.acme.broker.core.route.LoggingSpec;
import com.acme.broker.core.route.RouteDefinitionParser;
import com.acme.broker.runtime.config.BrokerProperties;
import com.acme.broker.runtime.config.InstanceId;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the two events of a request. Masking happens here, before anything reaches Kafka; {@code X-Token} stays
 * as it is so the legacy documents do not change.
 */
@Component
class EventFactory {

    private static final Set<String> DEFAULT_MASK = Set.of(LoggingSpec.ALWAYS_MASKED);

    private final String environment;
    private final String instance;

    EventFactory(BrokerProperties properties, InstanceId instanceId) {
        this.environment = properties.environment();
        this.instance = instanceId.value();
    }

    LogEvent request(Exchange x) {
        JsonNode body = null;
        if (x.body != null) {
            if (x.route == null || x.route.definition().logging().logRequestBody()) {
                body = Masker.mask(x.body, maskFields(x));
            } else {
                body = envelopeOnly(x.body);
            }
        }
        return event(x, LogEvent.EventType.REQUEST, x.receivedAt, null, null, null, body, x.rawBody.length, false,
                null, null);
    }

    /**
     * @param backendBody the service's body to log, or null; masked and trimmed here
     * @param bodySize    size of the service's body in bytes, when known
     */
    LogEvent response(Exchange x, Integer statusCode, Outcome outcome, JsonNode backendBody, Integer bodySize,
                      JsonNode clientResponse, String errorMessage) {
        JsonNode logged = null;
        boolean truncated = false;
        if (backendBody != null && !backendBody.isMissingNode()) {
            int limit = x.route == null ? LoggingSpec.DEFAULT_MAX_BODY_BYTES : x.route.definition().logging().maxBodyBytes();
            Masker.Capture capture = Masker.capture(Masker.mask(backendBody, maskFields(x)), limit);
            logged = capture.body();
            truncated = capture.truncated();
            if (bodySize == null) {
                bodySize = capture.size();
            }
        }
        long duration = (System.nanoTime() - x.startedNanos) / 1_000_000;
        return event(x, LogEvent.EventType.RESPONSE, Instant.now(), statusCode, outcome.name(), duration, logged,
                bodySize, truncated, clientResponse, errorMessage);
    }

    /** The response event of a request that got 500 because its log could not be written. */
    LogEvent logUnavailable(Exchange x) {
        return response(x, 500, Outcome.LOG_UNAVAILABLE, null, null, BrokerErrors.logUnavailableBody(),
                "Log olayı Kafka'ya yazılamadı");
    }

    /** {@code client_response}: what the frontend got, with its top-level {@code data} emptied as today. */
    static JsonNode clientResponse(JsonNode body) {
        if (body != null && body.isObject() && body.has("data")) {
            ObjectNode copy = (ObjectNode) body.deepCopy();
            copy.put("data", "");
            return copy;
        }
        return body;
    }

    private LogEvent event(Exchange x, LogEvent.EventType type, Instant time, Integer statusCode, String outcome,
                           Long durationMs, JsonNode body, Integer bodySize, Boolean truncated,
                           JsonNode clientResponse, String errorMessage) {
        boolean known = x.route != null;
        String maintask = known ? x.route.maintask() : x.knownProject != null ? x.knownProject : x.maintask;
        String httpMethod = known ? x.route.method().name() : null;
        String path = x.targetPath != null ? x.targetPath : known ? x.route.pathTemplate().source() : null;
        Integer profileVersion = known && x.route.profile() != null ? x.route.profile().version() : null;
        String profile = known && x.route.profile() != null ? x.route.profile().name() : null;
        return new LogEvent(x.requestId, x.trace.traceId(), type, time, environment, maintask,
                known ? x.route.subtask() : x.subtask, known ? x.route.version() : null, profile, profileVersion,
                x.application, x.uid, x.session == null ? null : x.session.userId(), x.queryId,
                x.session == null ? null : x.session.permissionList(), x.token, x.sessionOperation, x.clientIp,
                x.userAgent, httpMethod, path, statusCode, outcome, durationMs, body, bodySize, truncated,
                clientResponse, errorMessage, instance);
    }

    private static Set<String> maskFields(Exchange x) {
        return x.route == null ? DEFAULT_MASK : x.route.definition().logging().effectiveMaskFields();
    }

    private static JsonNode envelopeOnly(ObjectNode body) {
        ObjectNode envelope = Json.object();
        for (String field : RouteDefinitionParser.ENVELOPE_FIELDS) {
            if (body.has(field)) {
                envelope.set(field, body.get(field));
            }
        }
        return envelope;
    }
}
