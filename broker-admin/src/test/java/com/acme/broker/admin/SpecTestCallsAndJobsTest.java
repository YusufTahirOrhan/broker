package com.acme.broker.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.acme.broker.admin.catalog.MemoryProjectCatalog;
import com.acme.broker.admin.catalog.ProjectCatalog;
import com.acme.broker.admin.jobs.CatalogSync;
import com.acme.broker.admin.jobs.DriftCheck;
import com.acme.broker.admin.support.AdminTestBase;
import com.acme.broker.core.json.Json;
import com.acme.broker.core.testing.CoreFixtures;
import com.acme.broker.core.testing.JsonHttp;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class SpecTestCallsAndJobsTest extends AdminTestBase {

    @Autowired
    DriftCheck drift;

    @Autowired
    CatalogSync catalogSync;

    @Autowired
    ProjectCatalog catalog;

    @Autowired
    MeterRegistry meters;

    private static JsonNode item(JsonNode comparison, String group, String key) {
        return comparison.get("items").valueStream()
                .filter(item -> group.equals(item.get("group").stringValue())
                        && key.equals(item.get("httpMethod").stringValue() + " " + item.get("path").stringValue()))
                .findFirst().orElseThrow(() -> new AssertionError(group + " " + key + " yok: " + comparison));
    }

    @Test
    void specOperationsAreMatchedToSubtasksByMethodAndPath() {
        long projectId = project("order-service", "ORDER", "P-100");
        JsonNode first = get("viewer", "/projects/" + projectId + "/spec?env=DEV").json();
        assertThat(first.get("environment").stringValue()).isEqualTo("DEV");
        JsonNode notes = item(first, "NEW", "POST /orders/{id}/notes");
        assertThat(notes.get("proposal").get("definition").get("request").get("body").get("mode").stringValue())
                .isEqualTo("TEMPLATE");

        route(projectId, """
                {"subtask": "add_note", "httpMethod": "POST", "pathTemplate": "/orders/{id}/notes",
                 "permissions": ["ORDER_WRITE"], "definition": %s, "specOperation": %s}
                """.formatted(Json.write(notes.get("proposal").get("definition")), Json.write(notes.get("specOperation"))));
        route(projectId, """
                {"subtask": "get_order", "httpMethod": "GET", "pathTemplate": "/orders/{orderId}",
                 "permissions": ["ORDER_READ"],
                 "definition": {"request": {"fields": [{"name": "orderId", "type": "integer", "required": true}],
                                            "pathVariables": {"orderId": "${request.orderId}"}}}}
                """);
        route(projectId, """
                {"subtask": "legacy", "httpMethod": "GET", "pathTemplate": "/legacy", "permissions": ["ORDER_READ"],
                 "definition": {}}
                """);

        JsonNode second = get("viewer", "/projects/" + projectId + "/spec?env=DEV").json();
        assertThat(item(second, "SAME", "POST /orders/{id}/notes").get("route").get("subtask").stringValue())
                .isEqualTo("add_note");
        assertThat(item(second, "SAME", "GET /orders/{id}").get("route").get("subtask").stringValue())
                .isEqualTo("get_order");
        assertThat(item(second, "MISSING", "GET /legacy").get("route").get("subtask").stringValue()).isEqualTo("legacy");
        item(second, "NEW", "GET /reports/{id}/excel");
        assertThat(second.get("items").get(0).get("group").stringValue()).isEqualTo("MISSING");

        ObjectNode spec = (ObjectNode) CoreFixtures.sampleSpec();
        ((ObjectNode) spec.get("components").get("schemas").get("NoteRequest").get("properties"))
                .putObject("priority").put("type", "integer");
        ENVIRONMENTS.get("DEV").spec = spec;
        JsonNode changed = item(get("viewer", "/projects/" + projectId + "/spec?env=DEV").json(), "CHANGED",
                "POST /orders/{id}/notes");
        assertThat(changed.get("changes").toString()).contains("priority");
    }

    @Test
    void aPastedRequestBecomesADraft() {
        long projectId = project("order-service", "ORDER", "P-100");
        String curl = """
                curl 'https://app.example/broker' -H 'X-Token: 3f2b8c1e' -H 'Content-Type: application/json' \\
                  --data-raw '{"maintask":"order-service","subtask":"add_order_note","siparisNo":981,"text":"x","author_id":"u1","password":"p"}'
                """;
        JsonHttp.Response response = post("editor", "/projects/" + projectId + "/templates/from-sample", """
                {"text": %s, "environment": "DEV", "operation": {"httpMethod": "POST", "path": "/orders/{id}/notes"}}
                """.formatted(Json.write(Json.string(curl))));

        assertStatus(response, 200);
        JsonNode draft = response.json();
        assertThat(draft.get("subtask").stringValue()).isEqualTo("add_order_note");
        assertThat(draft.get("removedHeaders").valueStream().map(JsonNode::stringValue)).containsExactly("X-Token");
        assertThat(draft.get("maskedSample").get("password").stringValue()).isEqualTo("xxxx");
        assertThat(draft.get("proposal").get("definition").get("request").get("body").get("template"))
                .isEqualTo(Json.parse("{\"text\": \"${request.text}\", \"authorId\": \"${request.author_id}\"}"));
        assertThat(draft.get("unmatchedFields").valueStream().map(JsonNode::stringValue)).contains("siparisNo");

        JsonHttp.Response garbage = post("editor", "/projects/" + projectId + "/templates/from-sample",
                "{\"text\": \"merhaba\"}");
        assertThat(garbage.status()).isEqualTo(400);
        assertThat(garbage.json().get("errors").get(0).get("field").stringValue()).isEqualTo("text");
    }

    @Test
    void testCallsAndProbesGoThroughTheEnvironment() {
        long projectId = project("order-service", "ORDER", "P-100");
        long profileId = profile("order-service");
        String call = """
                {"environment": "%s", "httpMethod": "POST", "pathTemplate": "/orders/{id}/notes",
                 "responseProfileId": %d, "definition": %s, "sample": {"siparisNo": 5, "notMetni": "x"},
                 "headers": {"Accept-Language": "en"}}
                """;

        JsonHttp.Response result = post("editor", "/projects/" + projectId + "/test",
                call.formatted("DEV", profileId, CoreFixtures.ADD_ORDER_NOTE));
        assertStatus(result, 200);
        assertThat(result.json().get("outcome").stringValue()).isEqualTo("SUCCESS");
        JsonNode received = ENVIRONMENTS.get("DEV").testCalls.getFirst();
        assertThat(received.get("maintask").stringValue()).isEqualTo("order-service");
        assertThat(received.get("userId").stringValue()).isEqualTo("editor");
        assertThat(received.get("actor").stringValue()).isEqualTo("editor");
        assertThat(received.get("responseProfile").get("name").stringValue()).isEqualTo("order-service");
        assertThat(received.get("responseProfile").get("definition")).isEqualTo(Json.parse(CoreFixtures.LOG_PROJECT_PROFILE));

        JsonHttp.Response prod = post("editor", "/projects/" + projectId + "/test",
                call.formatted("PROD", profileId, CoreFixtures.ADD_ORDER_NOTE));
        assertThat(prod.status()).isEqualTo(403);
        assertThat(prod.json().get("code").stringValue()).isEqualTo("TEST_NOT_ALLOWED");
        JsonHttp.Response broken = post("editor", "/projects/" + projectId + "/test",
                call.formatted("DEV", profileId, "{\"timeoutMs\": 1}"));
        assertThat(broken.status()).isEqualTo(400);
        assertThat(broken.json().get("code").stringValue()).isEqualTo("VALIDATION_FAILED");
        assertThat(post("viewer", "/projects/" + projectId + "/test",
                call.formatted("DEV", profileId, CoreFixtures.ADD_ORDER_NOTE)).status()).isEqualTo(403);

        ENVIRONMENTS.get("TEST").probeResults.put("GET /orders/{id}", "WRONG_METHOD");
        JsonNode probe = post("editor", "/projects/" + projectId + "/probe", """
                {"environment": "TEST", "httpMethod": "GET", "pathTemplate": "/orders/{id}", "pathVariables": {"id": "7"}}
                """).json();
        assertThat(probe.get("result").stringValue()).isEqualTo("WRONG_METHOD");
    }

    @Test
    void environmentStatusShowsLaggingInstances() {
        project("order-service", "ORDER", "P-100");
        JsonNode status = get("viewer", "/environments/DEV/status").json();
        assertThat(status.get("instanceCount").intValue()).isEqualTo(2);
        assertThat(status.get("upToDateCount").intValue()).isEqualTo(1);
        assertThat(status.get("instances").get(1).get("lagging").booleanValue()).isTrue();
        assertThat(status.get("instances").get(0).get("lagging").booleanValue()).isFalse();
    }

    @Test
    void theNightlyDriftCheckProbesActiveRoutes() {
        long projectId = project("order-service", "ORDER", "P-100");
        long routeId = route(projectId, """
                {"subtask": "get_order", "httpMethod": "GET", "pathTemplate": "/orders", "permissions": ["ORDER_READ"],
                 "definition": {}}
                """);
        assertThat(post("editor", "/promotions", """
                {"subjectType": "ROUTE", "subjectId": %d, "version": 1, "targetEnv": "DEV", "action": "DEPLOY"}
                """.formatted(routeId)).json().get("status").stringValue()).isEqualTo("APPLIED");
        ENVIRONMENTS.get("DEV").probeResults.put("GET /orders", "PATH_NOT_FOUND");
        double before = count("PATH_NOT_FOUND");

        drift.run();

        JsonNode deployment = get("viewer", "/routes/" + routeId).json().get("deployments").get(0);
        assertThat(deployment.get("lastProbeStatus").stringValue()).isEqualTo("PATH_NOT_FOUND");
        assertThat(count("PATH_NOT_FOUND")).isEqualTo(before + 1);

        ENVIRONMENTS.get("DEV").routes.clear();
        double mismatches = count("RUNTIME_MISMATCH");
        drift.run();
        assertThat(count("RUNTIME_MISMATCH")).isEqualTo(mismatches + 1);
    }

    private double count(String result) {
        var counter = meters.find("broker.admin.drift").tags("environment", "DEV", "result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void catalogSyncRenamesAndMarksMissingProjects() {
        project("order-service", "ORDER", "P-100");
        project("crm", "CRM", "P-300");
        MemoryProjectCatalog memory = (MemoryProjectCatalog) catalog;
        memory.remove("P-100");
        memory.put("P-300", "CRM 2");
        try {
            catalogSync.run();
        } finally {
            memory.put("P-100", "Sipariş Servisi");
            memory.put("P-300", "CRM");
        }
        List<JsonNode> projects = get("viewer", "/projects").json().valueStream().toList();
        assertThat(projects).extracting(project -> project.get("maintask").stringValue() + " "
                        + project.get("catalogStatus").stringValue() + " " + project.get("name").stringValue())
                .containsExactly("crm LINKED CRM 2", "order-service MISSING Sipariş Servisi");
    }
}
