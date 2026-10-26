package com.acme.yapi.core.legacy;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.acme.yapi.core.ids.Ids;
import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.logging.EventCodec;
import com.acme.yapi.core.logging.LogEvent;
import com.acme.yapi.core.response.Outcome;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the Elasticsearch documents the old broker wrote, field for field, from log events. Only the {@code yapi}
 * object is new, so projects that read these indices keep working.
 *
 * <ul>
 *   <li>Main document, {@code _id} = request ID: the frontend body (password already masked), the
 *       {@code log_request} fields, {@code perm_list} and, with the response, {@code data_response}.</li>
 *   <li>Parameter documents, except for login and logout: one per field of every top-level value that is an object
 *       or a list of objects, with {@code parameter_value} in Java {@code toString} form.</li>
 * </ul>
 */
public final class LegacyDocuments {

    public static final Set<String> ENVELOPE = Set.of("maintask", "subtask", "application", "uid");
    private static final DateTimeFormatter REQUEST_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    public static final String ALARM_INDEX = "alarm";

    private final String indexPrefix;
    private final ZoneId indexZone;
    private final String alarmIndex;

    /**
     * @param indexPrefix {@code requests_}
     * @param indexZone   time zone the old code used to pick the monthly index
     */
    public LegacyDocuments(String indexPrefix, ZoneId indexZone) {
        this(indexPrefix, indexZone, ALARM_INDEX);
    }

    /** @param alarmIndex the legacy {@code alarm_log}'s index, {@code alarm} */
    public LegacyDocuments(String indexPrefix, ZoneId indexZone, String alarmIndex) {
        this.indexPrefix = indexPrefix;
        this.indexZone = indexZone;
        this.alarmIndex = alarmIndex;
    }

    public List<ElasticOperation> operations(LogEvent event) {
        String index = indexFor(event.requestId(), event.eventTime());
        if (event.eventType() == LogEvent.EventType.REQUEST) {
            List<ElasticOperation> operations = new ArrayList<>();
            operations.add(new ElasticOperation(ElasticOperation.Kind.UPSERT, index, event.requestId(), mainRequest(event)));
            if (event.sessionOperation() == null) {
                operations.addAll(parameterDocuments(event, index));
            }
            return operations;
        }
        List<ElasticOperation> operations = new ArrayList<>();
        operations.add(new ElasticOperation(ElasticOperation.Kind.UPSERT, index, event.requestId(), mainResponse(event)));
        ObjectNode alarm = alarmDocument(event);
        if (alarm != null) {
            operations.add(new ElasticOperation(ElasticOperation.Kind.INDEX, alarmIndex, event.queryId(), alarm));
        }
        return operations;
    }

    /**
     * The legacy {@code alarm_log}: under {@code query_id}, the request's parameters as they came (masked as in
     * the logs), and what is new in a {@code yapi} object. Only for a RESPONSE with alarms.
     */
    private static ObjectNode alarmDocument(LogEvent event) {
        JsonNode alarm = event.alarm();
        JsonNode alarms = alarm == null ? null : alarm.get("alarms");
        if (alarms == null || !alarms.isArray() || alarms.isEmpty() || event.queryId() == null) {
            return null;
        }
        ObjectNode document = Json.object();
        JsonNode params = alarm.get("params");
        if (params != null && params.isObject()) {
            for (Map.Entry<String, JsonNode> entry : Json.fields(params)) {
                document.set(entry.getKey(), entry.getValue());
            }
        }
        ObjectNode yapi = document.putObject("yapi");
        yapi.put("request_id", event.requestId());
        putIfPresent(yapi, "uid", event.uid());
        putIfPresent(yapi, "client_ip", event.clientIp());
        yapi.set("alarms", alarms);
        ObjectNode route = yapi.putObject("alarm_route");
        putIfPresent(route, "maintask", Json.stringField(alarm, "maintask"));
        putIfPresent(route, "subtask", Json.stringField(alarm, "subtask"));
        yapi.put("time", EventCodec.formatTime(event.eventTime()));
        putIfPresent(yapi, "environment", event.environment());
        return document;
    }

    /** {@code requests_yyyy-MM} from the time inside the UUIDv7 request ID, so both events land in one index. */
    public String indexFor(String requestId, Instant fallback) {
        Instant time = fallback;
        try {
            UUID uuid = UUID.fromString(requestId);
            if (uuid.version() == 7) {
                time = Ids.timeOf(uuid);
            }
        } catch (IllegalArgumentException ignored) {
            // not a UUID: fall back to the event time
        }
        return indexPrefix + DateTimeFormatter.ofPattern("yyyy-MM").withZone(indexZone).format(time);
    }

    private ObjectNode mainRequest(LogEvent event) {
        ObjectNode document = Json.object();
        if (event.body() != null && event.body().isObject()) {
            for (Map.Entry<String, JsonNode> entry : Json.fields(event.body())) {
                document.set(entry.getKey(), entry.getValue());
            }
        }
        putLogRequest(document, event, event.requestId());
        if (event.permList() != null) {
            ArrayNode permissions = document.putArray("perm_list");
            event.permList().forEach(permissions::add);
        }
        ObjectNode yapi = document.putObject("yapi");
        yapi.put("received_at", EventCodec.formatTime(event.eventTime()));
        putIfPresent(yapi, "trace_id", event.traceId());
        putIfPresent(yapi, "environment", event.environment());
        putIfPresent(yapi, "route_version", event.routeVersion());
        putIfPresent(yapi, "response_profile", event.responseProfile());
        putIfPresent(yapi, "response_profile_version", event.responseProfileVersion());
        putIfPresent(yapi, "user_id", event.userId());
        putIfPresent(yapi, "yapi_instance", event.yapiInstance());
        return document;
    }

    private ObjectNode mainResponse(LogEvent event) {
        ObjectNode document = Json.object();
        boolean logged = !Outcome.LOG_UNAVAILABLE.name().equals(event.outcome());
        if (logged && event.clientResponse() != null) {
            document.set("data_response", event.clientResponse());
        }
        ObjectNode yapi = document.putObject("yapi");
        yapi.put("responded_at", EventCodec.formatTime(event.eventTime()));
        putIfPresent(yapi, "status_code", event.statusCode());
        putIfPresent(yapi, "outcome", event.outcome());
        if (event.durationMs() != null) {
            yapi.put("duration_ms", event.durationMs().longValue());
        }
        if (event.bodyTruncated() != null) {
            yapi.put("body_truncated", event.bodyTruncated().booleanValue());
        }
        putIfPresent(yapi, "trace_id", event.traceId());
        putIfPresent(yapi, "environment", event.environment());
        putIfPresent(yapi, "yapi_instance", event.yapiInstance());
        return document;
    }

    private List<ElasticOperation> parameterDocuments(LogEvent event, String index) {
        List<ElasticOperation> operations = new ArrayList<>();
        JsonNode body = event.body();
        if (body == null || !body.isObject()) {
            return operations;
        }
        for (Map.Entry<String, JsonNode> entry : Json.fields(body)) {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            int position = 0;
            if (value.isObject()) {
                for (Map.Entry<String, JsonNode> parameter : Json.fields(value)) {
                    operations.add(parameterDocument(event, index, key, parameter, position++));
                }
            } else if (value.isArray() && containsObject(value)) {
                for (JsonNode element : value) {
                    if (element.isObject()) {
                        for (Map.Entry<String, JsonNode> parameter : Json.fields(element)) {
                            operations.add(parameterDocument(event, index, key, parameter, position++));
                        }
                    }
                }
            }
        }
        return operations;
    }

    private ElasticOperation parameterDocument(LogEvent event, String index, String key,
                                               Map.Entry<String, JsonNode> parameter, int position) {
        String id = Ids.parameterDocumentId(event.requestId(), key, position);
        ObjectNode document = Json.object();
        for (String field : List.of("maintask", "subtask", "application", "uid")) {
            JsonNode value = event.body().get(field);
            if (value != null) {
                document.set(field, value);
            }
        }
        putLogRequest(document, event, id);
        putIfPresent(document, "query_id", event.queryId());
        ObjectNode holder = document.putObject(key);
        holder.put("parameter_name", parameter.getKey());
        Object javaValue = Json.toJava(parameter.getValue());
        if (javaValue != null) {
            holder.put("parameter_value", String.valueOf(javaValue));
        }
        return new ElasticOperation(ElasticOperation.Kind.INDEX, index, id, document);
    }

    private static boolean containsObject(JsonNode array) {
        for (JsonNode element : array) {
            if (element.isObject()) {
                return true;
            }
        }
        return false;
    }

    private static void putLogRequest(ObjectNode document, LogEvent event, String requestId) {
        document.put("request_id", requestId);
        putOrNull(document, "client_ip", event.clientIp());
        putOrNull(document, "token", event.token());
        putOrNull(document, "user_agent", event.userAgent());
        document.put("request_date", REQUEST_DATE.format(event.eventTime()));
    }

    private static void putOrNull(ObjectNode node, String name, String value) {
        if (value == null) {
            node.putNull(name);
        } else {
            node.put(name, value);
        }
    }

    private static void putIfPresent(ObjectNode node, String name, String value) {
        if (value != null) {
            node.put(name, value);
        }
    }

    private static void putIfPresent(ObjectNode node, String name, Integer value) {
        if (value != null) {
            node.put(name, value.intValue());
        }
    }
}
