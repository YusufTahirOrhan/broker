package com.acme.yapi.runtime.web;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.logging.LogEvent;
import com.acme.yapi.core.logging.Masker;
import com.acme.yapi.core.response.Outcome;
import com.acme.yapi.core.route.LoggingSpec;
import com.acme.yapi.core.route.RouteDefinitionParser;
import com.acme.yapi.runtime.config.YapiProperties;
import com.acme.yapi.runtime.config.InstanceId;
import com.acme.yapi.runtime.route.Route;

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

    EventFactory(YapiProperties properties, InstanceId instanceId) {
        this.environment = properties.environment();
        this.instance = instanceId.value();
    }

    LogEvent request(Exchange x) {
        return event(x, LogEvent.EventType.REQUEST, x.receivedAt, null, null, null, requestBody(x), x.rawBody.length,
                false, null, null);
    }

    /** The frontend body as it is logged: masked, or only its envelope when the route does not log it. */
    private static JsonNode requestBody(Exchange x) {
        if (x.body == null) {
            return null;
        }
        // session operations are logged whole whatever the flag says; the password is always masked
        if (x.route == null || x.sessionOperation != null || x.route.definition().logging().logRequestBody()) {
            return Masker.mask(x.body, maskFields(x));
        }
        return envelopeOnly(x.body);
    }

    /** {@code client_response} as it is logged, and whether its text had to be cut to the route's limit. */
    record ClientLog(JsonNode value, boolean truncated) {

        static ClientLog of(JsonNode value) {
            return new ClientLog(value, false);
        }
    }

    LogEvent response(Exchange x, Integer statusCode, Outcome outcome, JsonNode backendBody, Integer bodySize,
                      JsonNode clientResponse, String errorMessage) {
        return response(x, statusCode, outcome, backendBody, bodySize, ClientLog.of(clientResponse), errorMessage);
    }

    /**
     * @param backendBody the service's body to log, or null; masked and trimmed here
     * @param bodySize    size of the service's body in bytes, when known
     * @param clientLog   what the frontend got, as {@link #clientResponse} or {@link #passedThrough} logs it; a cut
     *                    text marks the body as truncated ({@code yapi.body_truncated})
     */
    LogEvent response(Exchange x, Integer statusCode, Outcome outcome, JsonNode backendBody, Integer bodySize,
                      ClientLog clientLog, String errorMessage) {
        JsonNode logged = null;
        boolean truncated = clientLog.truncated();
        if (backendBody != null && !backendBody.isMissingNode()) {
            int limit = x.route == null ? LoggingSpec.DEFAULT_MAX_BODY_BYTES : x.route.definition().logging().maxBodyBytes();
            Masker.Capture capture = Masker.capture(Masker.mask(backendBody, maskFields(x)), limit);
            logged = capture.body();
            truncated |= capture.truncated();
            if (bodySize == null) {
                bodySize = capture.size();
            }
        }
        long duration = (System.nanoTime() - x.startedNanos) / 1_000_000;
        return event(x, LogEvent.EventType.RESPONSE, Instant.now(), statusCode, outcome.name(), duration, logged,
                bodySize, truncated, clientLog.value(), errorMessage);
    }

    /** The response event of a request that got 500 because its log could not be written. */
    LogEvent logUnavailable(Exchange x) {
        return response(x, 500, Outcome.LOG_UNAVAILABLE, null, null, YapiErrors.logUnavailableBody(),
                "Log olayı Kafka'ya yazılamadı");
    }

    /**
     * {@code client_response} of a body a response profile built. Without {@code logResponseBody} its top-level
     * {@code data} is emptied, as the legacy broker logged it; with it the body is masked and {@code data} becomes the
     * JSON text of the data (a string stays as it is), cut at {@code maxBodyBytes}. The field stays a string, so the
     * Elastic mapping of {@code data_response.data} does not change.
     */
    static ClientLog clientResponse(JsonNode body, LoggingSpec logging) {
        if (body == null || !body.isObject() || !body.has("data")) {
            return ClientLog.of(body);
        }
        if (!logging.logResponseBody()) {
            ObjectNode copy = (ObjectNode) body.deepCopy();
            copy.put("data", "");
            return ClientLog.of(copy);
        }
        ObjectNode masked = (ObjectNode) Masker.mask(body, logging.effectiveMaskFields());
        Masker.Capture data = Masker.text(masked.get("data"), logging.maxBodyBytes());
        masked.set("data", data.body());
        return new ClientLog(masked, data.truncated());
    }

    /**
     * {@code client_response} of a body passed through without a profile. Without {@code logResponseBody} only
     * {@code {"status": <code>}}, so data that is not to be logged does not reach {@code data_response}. With it the
     * masked body, its top-level {@code data} as JSON text when it is not a string; a body that is not a JSON object
     * goes into {@code data} as text. Larger than {@code maxBodyBytes}: {@code {"status": <code>, "data": "<cut text>"}}.
     */
    static ClientLog passedThrough(int status, JsonNode body, LoggingSpec logging) {
        ObjectNode statusOnly = Json.object();
        statusOnly.put("status", status);
        if (!logging.logResponseBody() || body == null || body.isMissingNode()) {
            return ClientLog.of(statusOnly);
        }
        JsonNode masked = Masker.mask(body, logging.effectiveMaskFields());
        ObjectNode logged;
        if (masked.isObject()) {
            logged = (ObjectNode) masked;
            JsonNode data = logged.get("data");
            if (data != null && !data.isString()) {
                logged.set("data", Masker.text(data, Integer.MAX_VALUE).body());
            }
        } else {
            logged = statusOnly.deepCopy();
            logged.set("data", Masker.text(masked, Integer.MAX_VALUE).body());
        }
        Masker.Capture size = Masker.text(logged, logging.maxBodyBytes());
        if (!size.truncated()) {
            return ClientLog.of(logged);
        }
        ObjectNode cut = statusOnly.deepCopy();
        cut.set("data", size.body());
        return new ClientLog(cut, true);
    }

    /**
     * {@code client_response} of a legacy body the broker returned itself, logged as the legacy broker logged every
     * response ({@code log_response.putAll(body); put("data", "")}).
     */
    static ClientLog legacyLogged(JsonNode body) {
        ObjectNode copy = (ObjectNode) body.deepCopy();
        copy.put("data", "");
        return ClientLog.of(copy);
    }

    /**
     * {@code client_response} of a login or logout: the login service's body with {@code "data": ""}, whatever the
     * route's log flag says; the {@code X-Token} of a login stays in it, as today.
     */
    static ClientLog sessionResponse(int status, JsonNode body) {
        if (body != null && body.isObject()) {
            return legacyLogged(body);
        }
        ObjectNode statusOnly = Json.object();
        statusOnly.put("status", status);
        return ClientLog.of(statusOnly);
    }

    private LogEvent event(Exchange x, LogEvent.EventType type, Instant time, Integer statusCode, String outcome,
                           Long durationMs, JsonNode body, Integer bodySize, Boolean truncated,
                           JsonNode clientResponse, String errorMessage) {
        boolean known = x.route != null;
        // a refused request names the route it asked for, even when the broker stopped before taking it
        Route named = known ? x.route : x.deniedRoute;
        // a session operation reaches the login service's route; the legacy document keeps the body's names
        boolean bodyNames = named == null || x.sessionOperation != null;
        String maintask = !bodyNames ? named.maintask() : x.knownProject != null ? x.knownProject : x.maintask;
        String httpMethod = known ? x.route.method().name() : null;
        String path = x.targetPath != null ? x.targetPath : known ? x.route.pathTemplate().source() : null;
        Integer profileVersion = known && x.route.profile() != null ? x.route.profile().version() : null;
        String profile = known && x.route.profile() != null ? x.route.profile().name() : null;
        return new LogEvent(x.requestId, x.trace.traceId(), type, time, environment, maintask,
                bodyNames ? x.subtask : named.subtask(), known ? x.route.version() : null, profile, profileVersion,
                x.application, x.uid, x.session == null ? null : x.session.userId(), x.queryId,
                x.session == null ? null : x.session.permissionList(), x.token, x.sessionOperation, x.clientIp,
                x.userAgent, httpMethod, path, statusCode, outcome, durationMs, body, bodySize, truncated,
                clientResponse, errorMessage, instance, type == LogEvent.EventType.RESPONSE ? x.alarm : null,
                type == LogEvent.EventType.RESPONSE ? denial(x) : null,
                type == LogEvent.EventType.RESPONSE ? applied(x) : null);
    }

    /** The plugins that worked on the request or its response, in the order they apply; null when none. */
    private static List<com.acme.yapi.core.plugin.Plugin.Applied> applied(Exchange x) {
        List<com.acme.yapi.core.plugin.Plugin.Applied> applied = x.plugins.stream()
                .map(com.acme.yapi.core.plugin.Plugin::applied).filter(x.appliedPlugins::contains).toList();
        return applied.isEmpty() ? null : applied;
    }

    private static LogEvent.Denial denial(Exchange x) {
        if (x.deniedReason == null) {
            return null;
        }
        return new LogEvent.Denial(x.deniedReason, x.deniedRoute == null ? null : x.deniedRoute.permissions(),
                x.hasProjectPermission, requestBody(x));
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
