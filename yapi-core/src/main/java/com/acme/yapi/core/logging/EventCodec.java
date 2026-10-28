package com.acme.yapi.core.logging;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import com.acme.yapi.core.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** The wire format of {@link LogEvent} on {@code yapi.logs.v1}: snake_case JSON, one event per message. */
public final class EventCodec {

    private EventCodec() {
    }

    public static ObjectNode toJson(LogEvent event) {
        ObjectNode node = Json.object();
        node.put("request_id", event.requestId());
        put(node, "trace_id", event.traceId());
        node.put("event_type", event.eventType().name());
        node.put("event_time", formatTime(event.eventTime()));
        put(node, "environment", event.environment());
        put(node, "maintask", event.maintask());
        put(node, "subtask", event.subtask());
        put(node, "route_version", event.routeVersion());
        put(node, "response_profile", event.responseProfile());
        put(node, "response_profile_version", event.responseProfileVersion());
        put(node, "application", event.application());
        put(node, "uid", event.uid());
        put(node, "user_id", event.userId());
        put(node, "query_id", event.queryId());
        if (event.permList() == null) {
            node.putNull("perm_list");
        } else {
            ArrayNode permissions = node.putArray("perm_list");
            event.permList().forEach(permissions::add);
        }
        put(node, "token", event.token());
        put(node, "session_operation", event.sessionOperation());
        put(node, "client_ip", event.clientIp());
        put(node, "user_agent", event.userAgent());
        put(node, "http_method", event.httpMethod());
        put(node, "target_path", event.targetPath());
        put(node, "status_code", event.statusCode());
        put(node, "outcome", event.outcome());
        if (event.durationMs() == null) {
            node.putNull("duration_ms");
        } else {
            node.put("duration_ms", event.durationMs().longValue());
        }
        node.set("body", event.body() == null ? Json.nullNode() : event.body());
        put(node, "body_size", event.bodySize());
        if (event.bodyTruncated() == null) {
            node.putNull("body_truncated");
        } else {
            node.put("body_truncated", event.bodyTruncated().booleanValue());
        }
        node.set("client_response", event.clientResponse() == null ? Json.nullNode() : event.clientResponse());
        put(node, "error_message", event.errorMessage());
        put(node, "yapi_instance", event.yapiInstance());
        node.set("alarm", event.alarm() == null ? Json.nullNode() : event.alarm());
        LogEvent.Denial denial = event.denial();
        put(node, "denied_reason", denial == null ? null : denial.reason().name());
        if (denial == null || denial.routePermissions() == null) {
            node.putNull("route_permissions");
        } else {
            node.set("route_permissions", Json.stringArray(denial.routePermissions()));
        }
        if (denial == null || denial.hasProjectPermission() == null) {
            node.putNull("has_project_permission");
        } else {
            node.put("has_project_permission", denial.hasProjectPermission().booleanValue());
        }
        node.set("request_body", denial == null || denial.requestBody() == null ? Json.nullNode() : denial.requestBody());
        return node;
    }

    public static byte[] toBytes(LogEvent event) {
        return Json.writeBytes(toJson(event));
    }

    public static LogEvent fromJson(JsonNode node) {
        List<String> permissions = strings(node.get("perm_list"));
        String eventTime = string(node, "event_time");
        return new LogEvent(
                string(node, "request_id"),
                string(node, "trace_id"),
                LogEvent.EventType.valueOf(string(node, "event_type")),
                eventTime == null ? null : Instant.parse(eventTime),
                string(node, "environment"),
                string(node, "maintask"),
                string(node, "subtask"),
                integer(node, "route_version"),
                string(node, "response_profile"),
                integer(node, "response_profile_version"),
                string(node, "application"),
                string(node, "uid"),
                string(node, "user_id"),
                string(node, "query_id"),
                permissions,
                string(node, "token"),
                string(node, "session_operation"),
                string(node, "client_ip"),
                string(node, "user_agent"),
                string(node, "http_method"),
                string(node, "target_path"),
                integer(node, "status_code"),
                string(node, "outcome"),
                longValue(node, "duration_ms"),
                nullToNull(node.get("body")),
                integer(node, "body_size"),
                booleanValue(node, "body_truncated"),
                nullToNull(node.get("client_response")),
                string(node, "error_message"),
                string(node, "yapi_instance"),
                nullToNull(node.get("alarm")),
                denial(node));
    }

    private static LogEvent.Denial denial(JsonNode node) {
        String reason = string(node, "denied_reason");
        if (reason == null) {
            return null;
        }
        return new LogEvent.Denial(LogEvent.DeniedReason.valueOf(reason), strings(node.get("route_permissions")),
                booleanValue(node, "has_project_permission"), nullToNull(node.get("request_body")));
    }

    public static LogEvent fromBytes(byte[] bytes) {
        return fromJson(Json.parse(bytes));
    }

    public static String formatTime(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MILLIS).toString();
    }

    private static void put(ObjectNode node, String name, String value) {
        if (value == null) {
            node.putNull(name);
        } else {
            node.put(name, value);
        }
    }

    private static void put(ObjectNode node, String name, Integer value) {
        if (value == null) {
            node.putNull(name);
        } else {
            node.put(name, value.intValue());
        }
    }

    private static String string(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return Json.isAbsent(value) ? null : Json.text(value);
    }

    private static Integer integer(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return Json.isAbsent(value) || !value.isNumber() ? null : value.intValue();
    }

    private static Long longValue(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return Json.isAbsent(value) || !value.isNumber() ? null : value.longValue();
    }

    private static Boolean booleanValue(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return Json.isAbsent(value) || !value.isBoolean() ? null : value.booleanValue();
    }

    private static List<String> strings(JsonNode array) {
        if (array == null || !array.isArray()) {
            return null;
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : array) {
            values.add(Json.text(value));
        }
        return values;
    }

    private static JsonNode nullToNull(JsonNode value) {
        return Json.isAbsent(value) ? null : value;
    }
}
