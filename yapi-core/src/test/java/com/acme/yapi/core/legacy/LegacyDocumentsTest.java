package com.acme.yapi.core.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.acme.yapi.core.ids.Ids;
import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.logging.EventCodec;
import com.acme.yapi.core.logging.LogEvent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class LegacyDocumentsTest {

    private static final Instant RECEIVED = Instant.parse("2026-10-31T23:59:59.900Z");
    private static final String REQUEST_ID = Ids.uuidV7(RECEIVED.toEpochMilli()).toString();
    private final LegacyDocuments documents = new LegacyDocuments("requests_", ZoneId.of("UTC"));

    private static LogEvent request(String sessionOperation, String body) {
        return new LogEvent(REQUEST_ID, "trace", LogEvent.EventType.REQUEST, RECEIVED, "PROD", "logProject",
                "get_logs", 3, "log-project", 2, "web", "u1", "u1", "b1f4c2d8-6e3a-4f5b-9c7d-2a8e0f1b3c4d",
                List.of("LOGPROJECT_TEMEL"), "token-1", sessionOperation, "10.0.0.1", "agent", "POST", "/logs",
                null, null, null, Json.parse(body), null, null, null, null, "yapi-1", null, null);
    }

    private static LogEvent response(String outcome) {
        return response(outcome, "{\"BACKEND_STATUS\": 200, \"status\": 200, \"data\": \"\"}", false);
    }

    private static LogEvent response(String outcome, String clientResponse, boolean truncated) {
        return new LogEvent(REQUEST_ID, "trace", LogEvent.EventType.RESPONSE, RECEIVED.plusSeconds(1), "PROD",
                "logProject", "get_logs", 3, "log-project", 2, "web", "u1", "u1", null, null, "token-1", null,
                "10.0.0.1", "agent", "POST", "/logs", 200, outcome, 87L, null, 10, truncated,
                Json.parse(clientResponse), null, "yapi-1", null, null);
    }

    private static final String BODY = """
            {"maintask": "LogProject", "subtask": "get_logs", "application": "web", "uid": "u1", "password": "xxxx",
             "filtre": {"tarih": "2026-10-01", "adet": 10, "alt": {"a": 1}, "oran": 1.5},
             "liste": [{"k": 1, "v": null}, {"k": 2}], "duz": "x", "sayilar": [1, 2]}
            """;

    @Test
    void mainDocumentKeepsTheOldFields() {
        List<ElasticOperation> operations = documents.operations(request(null, BODY));
        ElasticOperation main = operations.getFirst();
        assertThat(main.kind()).isEqualTo(ElasticOperation.Kind.UPSERT);
        assertThat(main.id()).isEqualTo(REQUEST_ID);
        assertThat(main.index()).isEqualTo("requests_2026-10");

        ObjectNode doc = main.document();
        assertThat(Json.text(doc.get("maintask"))).isEqualTo("LogProject");
        assertThat(Json.text(doc.get("password"))).isEqualTo("xxxx");
        assertThat(Json.text(doc.get("request_id"))).isEqualTo(REQUEST_ID);
        assertThat(Json.text(doc.get("request_date"))).isEqualTo("2026-10-31T23:59:59");
        assertThat(Json.text(doc.get("token"))).isEqualTo("token-1");
        assertThat(Json.text(doc.get("client_ip"))).isEqualTo("10.0.0.1");
        assertThat(Json.text(doc.get("user_agent"))).isEqualTo("agent");
        assertThat(doc.get("perm_list").get(0).stringValue()).isEqualTo("LOGPROJECT_TEMEL");
        assertThat(doc.has("query_id")).isFalse();
        assertThat(doc.get("yapi").get("route_version").intValue()).isEqualTo(3);
    }

    @Test
    void oneParameterDocumentPerFieldOfObjectsAndObjectLists() {
        List<ElasticOperation> parameters = documents.operations(request(null, BODY)).subList(1, 8);
        assertThat(parameters).hasSize(7);
        assertThat(parameters).allMatch(operation -> operation.kind() == ElasticOperation.Kind.INDEX);

        List<String> values = parameters.stream().map(operation -> {
            JsonNode holder = operation.document().has("filtre")
                    ? operation.document().get("filtre") : operation.document().get("liste");
            JsonNode value = holder.get("parameter_value");
            return holder.get("parameter_name").stringValue() + "=" + (value == null ? "<yok>" : value.stringValue());
        }).toList();
        assertThat(values).containsExactly("tarih=2026-10-01", "adet=10", "alt={a=1}", "oran=1.5", "k=1", "v=<yok>",
                "k=2");

        JsonNode first = parameters.getFirst().document();
        assertThat(Json.text(first.get("query_id"))).isEqualTo("b1f4c2d8-6e3a-4f5b-9c7d-2a8e0f1b3c4d");
        assertThat(Json.text(first.get("request_id"))).isNotEqualTo(REQUEST_ID).isEqualTo(parameters.getFirst().id());
        assertThat(Json.text(first.get("maintask"))).isEqualTo("LogProject");
        assertThat(first.has("perm_list")).isFalse();
        assertThat(first.has("password")).isFalse();
    }

    @Test
    void replayedEventsProduceTheSameIds() {
        List<String> once = documents.operations(request(null, BODY)).stream().map(ElasticOperation::id).toList();
        List<String> twice = documents.operations(request(null, BODY)).stream().map(ElasticOperation::id).toList();
        assertThat(once).isEqualTo(twice).doesNotHaveDuplicates();
    }

    @Test
    void loginAndLogoutHaveNoParameterDocuments() {
        assertThat(documents.operations(request("LOGIN", BODY))).hasSize(1);
    }

    @Test
    void responseAddsDataResponseUnlessTheLogFailed() {
        ObjectNode ok = documents.operations(response("SUCCESS")).getFirst().document();
        assertThat(ok.get("data_response").get("BACKEND_STATUS").intValue()).isEqualTo(200);
        assertThat(Json.text(ok.get("yapi").get("outcome"))).isEqualTo("SUCCESS");

        ObjectNode failed = documents.operations(response("LOG_UNAVAILABLE")).getFirst().document();
        assertThat(failed.has("data_response")).isFalse();
        assertThat(Json.text(failed.get("yapi").get("outcome"))).isEqualTo("LOG_UNAVAILABLE");
    }

    @Test
    void loggedResponseDataStaysAStringAndACutIsMarked() {
        ObjectNode logged = documents.operations(response("SUCCESS",
                "{\"BACKEND_STATUS\": 200, \"status\": 200, \"data\": \"{\\\"id\\\":7,\\\"tckn\\\":\\\"xxxx\\\"}\"}",
                false)).getFirst().document();
        assertThat(logged.get("data_response").get("data").isString()).isTrue();
        assertThat(Json.text(logged.get("data_response").get("data"))).isEqualTo("{\"id\":7,\"tckn\":\"xxxx\"}");
        assertThat(logged.get("yapi").get("body_truncated").booleanValue()).isFalse();

        ObjectNode cut = documents.operations(response("SUCCESS",
                "{\"BACKEND_STATUS\": 200, \"status\": 200, \"data\": \"{\\\"id\\\":7,\\\"ad\\\":\\\"Ça\"}", true))
                .getFirst().document();
        assertThat(Json.text(cut.get("data_response").get("data"))).isEqualTo("{\"id\":7,\"ad\":\"Ça");
        assertThat(cut.get("yapi").get("body_truncated").booleanValue()).isTrue();
    }

    @Test
    void responseLandsInTheRequestsMonthEvenAfterMidnight() {
        assertThat(documents.operations(response("SUCCESS")).getFirst().index()).isEqualTo("requests_2026-10");
        assertThat(Ids.timeOf(UUID.fromString(REQUEST_ID))).isEqualTo(Instant.ofEpochMilli(RECEIVED.toEpochMilli()));
    }

    @Test
    void aResponseWithAlarmsAlsoWritesTheLegacyAlarmLog() {
        LogEvent alarmed = new LogEvent(REQUEST_ID, "trace", LogEvent.EventType.RESPONSE, RECEIVED.plusSeconds(1),
                "PROD", "sorgu", "kisi_sorgu", 3, null, null, "web", "12345", "12345",
                "b1f4c2d8-6e3a-4f5b-9c7d-2a8e0f1b3c4d", null, "token-1", null, "10.0.0.1", "agent", "POST",
                null, 200, "ALARM", 12L, null, null, null,
                Json.parse("{\"response\": false, \"badprocess\": true, \"status\": 500, \"data\": \"\"}"), null,
                "yapi-1", Json.parse("""
                        {"maintask": "guvenlik", "subtask": "alarm_kontrol", "alarms": ["10000000146"],
                         "params": {"maintask": "sorgu", "subtask": "kisi_sorgu", "uid": "12345",
                                    "tckn": "10000000146", "password": "xxxx"}}
                        """), null);

        List<ElasticOperation> operations = documents.operations(alarmed);

        assertThat(operations).hasSize(2);
        ElasticOperation alarm = operations.get(1);
        assertThat(alarm.kind()).isEqualTo(ElasticOperation.Kind.INDEX);
        assertThat(alarm.index()).isEqualTo("alarm");
        assertThat(alarm.id()).isEqualTo("b1f4c2d8-6e3a-4f5b-9c7d-2a8e0f1b3c4d");
        assertThat(alarm.document()).isEqualTo(Json.parse("""
                {"maintask": "sorgu", "subtask": "kisi_sorgu", "uid": "12345", "tckn": "10000000146", "password": "xxxx",
                 "yapi": {"request_id": "%s", "uid": "12345", "client_ip": "10.0.0.1", "alarms": ["10000000146"],
                          "alarm_route": {"maintask": "guvenlik", "subtask": "alarm_kontrol"},
                          "time": "2026-11-01T00:00:00.900Z", "environment": "PROD"}}
                """.formatted(REQUEST_ID)));
        assertThat(operations.getFirst().document().get("yapi").get("outcome").stringValue()).isEqualTo("ALARM");
    }

    @Test
    void noAlarmLogWithoutAlarms() {
        LogEvent failed = new LogEvent(REQUEST_ID, "trace", LogEvent.EventType.RESPONSE, RECEIVED.plusSeconds(1),
                "PROD", "sorgu", "kisi_sorgu", 3, null, null, "web", "12345", "12345", "q-1", null, "token-1", null,
                "10.0.0.1", "agent", "POST", null, 503, "TECHNICAL_ERROR", 12L, null, null, null, Json.parse("{}"),
                "Alarm servisi 500 döndü", "yapi-1", Json.parse("""
                        {"maintask": "guvenlik", "subtask": "alarm_kontrol", "error": "Alarm servisi 500 döndü"}
                        """), null);

        assertThat(documents.operations(failed)).hasSize(1);
        assertThat(EventCodec.fromJson(EventCodec.toJson(failed)).alarm()).isEqualTo(failed.alarm());
        assertThat(documents.operations(response("SUCCESS"))).hasSize(1);
    }

    private static LogEvent denied(LogEvent.Denial denial, Integer status, String userId, List<String> permissions) {
        return new LogEvent(REQUEST_ID, "trace", LogEvent.EventType.RESPONSE, RECEIVED.plusSeconds(1), "PROD",
                "order-service", "create_order", 3, null, null, "web", "u1", userId, "q-1", permissions, "token-1",
                null, "10.0.0.1", "agent", "POST", "/orders", status, "DENIED", 4L, null, null, null,
                Json.parse("{\"result\": false}"), null, "yapi-1", null, denial);
    }

    @Test
    void aRefusedRequestIsAlsoWrittenToTheUnauthorizedIndex() {
        JsonNode body = Json.parse("""
                {"maintask": "Order-Service", "subtask": "CREATE_ORDER", "application": "web", "uid": "u1",
                 "password": "xxxx", "items": [{"sku": "A-1"}]}
                """);
        LogEvent refused = denied(new LogEvent.Denial(LogEvent.DeniedReason.PERMISSION,
                List.of("ORDER_WRITE", "CRM_AGENT"), false, body), 403, "u1", List.of("CRM_READ"));

        List<ElasticOperation> operations = documents.operations(refused);

        assertThat(operations).hasSize(2);
        assertThat(operations.getFirst().document().has("denied_reason")).isFalse();
        assertThat(operations.getFirst().document().get("yapi").has("denied_reason")).isFalse();
        ElasticOperation unauthorized = operations.get(1);
        assertThat(unauthorized.kind()).isEqualTo(ElasticOperation.Kind.INDEX);
        assertThat(unauthorized.index()).isEqualTo("yapi_unauthorized");
        assertThat(unauthorized.id()).isEqualTo(REQUEST_ID);
        assertThat(unauthorized.document()).isEqualTo(Json.parse("""
                {"request_id": "%s", "time": "2026-10-31T23:59:59.900Z", "environment": "PROD",
                 "denied_reason": "PERMISSION", "status": 403, "uid": "u1", "user_id": "u1", "application": "web",
                 "maintask": "Order-Service", "subtask": "CREATE_ORDER", "route_maintask": "order-service",
                 "route_subtask": "create_order", "route_permissions": ["ORDER_WRITE", "CRM_AGENT"],
                 "user_permissions": ["CRM_READ"], "has_project_permission": false, "client_ip": "10.0.0.1",
                 "user_agent": "agent", "body": %s}
                """.formatted(REQUEST_ID, Json.write(Json.string(Json.write(body))))));
        assertThat(unauthorized.document().has("token")).isFalse();
    }

    @Test
    void withoutARouteOrAVerifiedUserTheRouteFieldsAreNull() {
        JsonNode body = Json.parse("{\"maintask\": \"nowhere\", \"subtask\": \"none\", \"uid\": \"u1\"}");
        LogEvent refused = denied(new LogEvent.Denial(LogEvent.DeniedReason.SESSION, null, null, body), 200, null,
                null);

        JsonNode document = documents.operations(refused).get(1).document();

        assertThat(document.get("denied_reason").stringValue()).isEqualTo("SESSION");
        assertThat(document.get("route_permissions").isNull()).isTrue();
        assertThat(document.get("route_maintask").isNull()).isTrue();
        assertThat(document.get("has_project_permission").isNull()).isTrue();
        assertThat(document.get("user_permissions").isNull()).isTrue();
        assertThat(document.get("user_id").isNull()).isTrue();
        assertThat(document.get("maintask").stringValue()).isEqualTo("nowhere");
        LogEvent decoded = EventCodec.fromJson(EventCodec.toJson(refused));
        assertThat(decoded.denial()).isEqualTo(refused.denial());
        JsonNode wire = EventCodec.toJson(refused);
        assertThat(wire.get("denied_reason").stringValue()).isEqualTo("SESSION");
        assertThat(wire.has("request_body")).isTrue();
        assertThat(EventCodec.toJson(response("SUCCESS")).get("denied_reason").isNull()).isTrue();
        assertThat(EventCodec.fromJson(EventCodec.toJson(response("SUCCESS"))).denial()).isNull();
    }
}
