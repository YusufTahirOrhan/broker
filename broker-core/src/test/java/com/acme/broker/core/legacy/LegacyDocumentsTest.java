package com.acme.broker.core.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.acme.broker.core.ids.Ids;
import com.acme.broker.core.json.Json;
import com.acme.broker.core.logging.LogEvent;

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
                null, null, null, Json.parse(body), null, null, null, null, "broker-1");
    }

    private static LogEvent response(String outcome) {
        return new LogEvent(REQUEST_ID, "trace", LogEvent.EventType.RESPONSE, RECEIVED.plusSeconds(1), "PROD",
                "logProject", "get_logs", 3, "log-project", 2, "web", "u1", "u1", null, null, "token-1", null,
                "10.0.0.1", "agent", "POST", "/logs", 200, outcome, 87L, null, 10, false,
                Json.parse("{\"BACKEND_STATUS\": 200, \"status\": 200, \"data\": \"\"}"), null, "broker-1");
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
        assertThat(doc.get("broker").get("route_version").intValue()).isEqualTo(3);
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
        assertThat(Json.text(ok.get("broker").get("outcome"))).isEqualTo("SUCCESS");

        ObjectNode failed = documents.operations(response("LOG_UNAVAILABLE")).getFirst().document();
        assertThat(failed.has("data_response")).isFalse();
        assertThat(Json.text(failed.get("broker").get("outcome"))).isEqualTo("LOG_UNAVAILABLE");
    }

    @Test
    void responseLandsInTheRequestsMonthEvenAfterMidnight() {
        assertThat(documents.operations(response("SUCCESS")).getFirst().index()).isEqualTo("requests_2026-10");
        assertThat(Ids.timeOf(UUID.fromString(REQUEST_ID))).isEqualTo(Instant.ofEpochMilli(RECEIVED.toEpochMilli()));
    }
}
