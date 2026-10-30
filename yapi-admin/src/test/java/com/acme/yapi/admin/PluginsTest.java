package com.acme.yapi.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.acme.yapi.admin.support.AdminTestBase;
import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.testing.CoreFixtures;
import com.acme.yapi.core.testing.JsonHttp;

import tools.jackson.databind.JsonNode;

class PluginsTest extends AdminTestBase {

    private static final String REQUEST_TIME = """
            {"name": "request-time", "description": "İsteğe tarih ekler", "comment": "ilk",
             "definition": {"scope": "SUBTASK",
                            "request": [{"op": "set", "target": "header", "name": "X-Request-Time", "value": "${now}"},
                                        {"op": "set", "target": "header", "name": "X-Channel", "value": "PLUGIN"}],
                            "response": [{"op": "set", "target": "body", "name": "meta.outcome", "value": "${outcome}"}]}}
            """;
    private static final String EVERYWHERE = """
            {"name": "everywhere", "definition": {"scope": "GLOBAL",
             "request": [{"op": "setIfAbsent", "target": "header", "name": "X-Trace-Id", "value": "${traceId}"}]}}
            """;

    private long create(String user, String body) {
        JsonHttp.Response created = post(user, "/plugins", body);
        assertStatus(created, 201);
        return created.json().get("id").longValue();
    }

    private JsonHttp.Response promote(String user, long plugin, int version, String target, String reason) {
        return post(user, "/promotions", """
                {"subjectType": "PLUGIN", "subjectId": %d, "version": %d, "targetEnv": "%s", "action": "DEPLOY",
                 "reason": %s}
                """.formatted(plugin, version, target, reason == null ? "null" : "\"" + reason + "\""));
    }

    private static JsonNode check(JsonNode response, String name) {
        return response.get("checks").valueStream().filter(check -> name.equals(check.get("check").stringValue()))
                .findFirst().orElseThrow(() -> new AssertionError(name + " yok: " + response));
    }

    @Test
    void pluginsAreVersionedLikeProfilesAndGlobalOnesNeedAnApprover() {
        long id = create("editor", REQUEST_TIME);
        assertThat(post("editor", "/plugins", REQUEST_TIME).json().get("code").stringValue()).isEqualTo("DUPLICATE");

        JsonHttp.Response invalid = post("editor", "/plugins", """
                {"name": "broken", "definition": {"scope": "PROJECT", "projects": ["nowhere"],
                 "request": [{"op": "set", "target": "header", "name": "X-Took", "value": "${durationMs}"}]}}
                """);
        assertStatus(invalid, 400);
        assertThat(invalid.json().get("errors").valueStream().map(error -> error.get("field").stringValue()))
                .containsExactly("definition.request[0].value");
        project("order-service", "ORDER", "P-100");
        JsonHttp.Response unknownProject = post("editor", "/plugins", """
                {"name": "broken", "definition": {"scope": "PROJECT", "projects": ["order-service", "nowhere"],
                 "request": [{"op": "remove", "target": "header", "name": "X-A"}]}}
                """);
        assertThat(unknownProject.json().get("errors").get(0).get("field").stringValue())
                .isEqualTo("definition.projects[1]");

        assertStatus(post("editor", "/plugins", EVERYWHERE), 403);
        long global = create("lead", EVERYWHERE);
        assertStatus(put("editor", "/plugins/" + global, """
                {"definition": {"scope": "SUBTASK", "request": [{"op": "remove", "target": "header", "name": "X-A"}]},
                 "version": 1}
                """), 403);
        // making a plugin GLOBAL is the approver's too
        assertStatus(put("editor", "/plugins/" + id, EVERYWHERE.replace("\"everywhere\"", "\"request-time\"")
                .replace("\"definition\"", "\"version\": 1, \"definition\"")), 403);

        String changed = REQUEST_TIME.replace("\"PLUGIN\"", "\"PLUGIN-2\"").replace("\"comment\": \"ilk\"",
                "\"comment\": \"kanal\", \"version\": 1");
        JsonHttp.Response updated = put("editor", "/plugins/" + id, changed);
        assertStatus(updated, 200);
        assertThat(updated.json().get("version").intValue()).isEqualTo(2);
        assertThat(put("editor", "/plugins/" + id, changed).json().get("code").stringValue()).isEqualTo("VERSION_CONFLICT");
        JsonNode comparison = get("viewer", "/plugins/" + id + "/compare?from=1&to=2").json();
        assertThat(comparison.get("changes").valueStream().map(change -> change.get("path").stringValue()))
                .contains("definition.request[1].value");
        assertThat(post("editor", "/plugins/" + id + "/rollback", "{\"restoreVersion\": 1}").json()
                .get("version").intValue()).isEqualTo(3);
        assertThat(get("viewer", "/plugins/" + id + "/history").json().get(0).get("action").stringValue())
                .isEqualTo("ROLLBACK");
        JsonNode listed = get("viewer", "/plugins").json();
        assertThat(listed.valueStream().map(plugin -> plugin.get("name").stringValue() + " "
                + plugin.get("scope").stringValue())).containsExactly("everywhere GLOBAL", "request-time SUBTASK");
    }

    @Test
    void thePreviewShowsWhatThePluginDoesToASubtask() {
        JsonNode preview = post("viewer", "/plugins/preview", """
                {"definition": %s,
                 "request": {"maintask": "order-service", "subtask": "add_order_note", "httpMethod": "POST",
                             "pathTemplate": "/orders/{id}/notes", "definition": %s,
                             "sample": {"siparisNo": 42, "notMetni": "x"}, "headers": {"Accept-Language": "tr"}},
                 "response": {"status": 201, "body": {"noteId": 9}}}
                """.formatted(Json.parse(REQUEST_TIME).get("definition"), CoreFixtures.ADD_ORDER_NOTE)).json();

        JsonNode headers = preview.get("request").get("headers");
        assertThat(headers.get("X-Request-Time").stringValue()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}");
        // the definition's own header stays
        assertThat(headers.get("X-Channel").stringValue()).isEqualTo("WEB");
        assertThat(preview.get("response").get("body"))
                .isEqualTo(Json.parse("{\"noteId\": 9, \"meta\": {\"outcome\": \"SUCCESS\"}}"));
        assertThat(preview.get("errors")).isEmpty();
    }

    @Test
    void subtasksChooseActivePluginsAndPreviewsAndTestsApplyThem() {
        long projectId = project("order-service", "ORDER", "P-100");
        long plugin = create("editor", REQUEST_TIME);
        long global = create("lead", EVERYWHERE);
        String chosen = CoreFixtures.ADD_ORDER_NOTE.replaceFirst("\"response\"", "\"plugins\": [%s], \"response\"");
        String body = """
                {"subtask": "add_order_note", "httpMethod": "POST", "pathTemplate": "/orders/{id}/notes",
                 "permissions": ["ORDER_WRITE"], "definition": %s}
                """;

        JsonHttp.Response unknown = post("editor", "/projects/" + projectId + "/routes",
                body.formatted(chosen.formatted("\"nope\"")));
        assertStatus(unknown, 400);
        assertThat(unknown.json().get("errors").get(0).get("field").stringValue()).isEqualTo("definition.plugins[0]");
        JsonHttp.Response notSubtask = post("editor", "/projects/" + projectId + "/routes",
                body.formatted(chosen.formatted("\"everywhere\"")));
        assertThat(notSubtask.json().get("errors").get(0).get("message").stringValue())
                .contains("Yalnızca SUBTASK kapsamlı eklenti seçilir");
        long routeId = route(projectId, body.formatted(chosen.formatted("\"Request-Time\"")));

        JsonHttp.Response early = post("editor", "/promotions", """
                {"subjectType": "ROUTE", "subjectId": %d, "version": 1, "targetEnv": "DEV", "action": "DEPLOY"}
                """.formatted(routeId));
        assertThat(early.status()).as(early.text()).isEqualTo(422);
        assertThat(check(early.json(), "PLUGINS_ACTIVE").get("message").stringValue())
                .isEqualTo("DEV ortamında aktif olmayan eklenti: Request-Time; önce eklenti taşınmalı");

        assertStatus(promote("editor", global, 1, "DEV", null), 403);
        JsonHttp.Response deployed = promote("editor", plugin, 1, "DEV", null);
        assertStatus(deployed, 201);
        assertThat(deployed.json().get("status").stringValue()).as(deployed.text()).isEqualTo("APPLIED");
        assertThat(deployed.json().get("subjectType").stringValue()).isEqualTo("PLUGIN");
        assertThat(deployed.json().get("subjectName").stringValue()).isEqualTo("request-time");
        JsonNode sent = ENVIRONMENTS.get("DEV").plugins.get("request-time");
        assertThat(sent.get("version").intValue()).isEqualTo(1);
        assertThat(sent.get("definition").get("scope").stringValue()).isEqualTo("SUBTASK");
        assertThat(get("viewer", "/plugins/" + plugin).json().get("deployments").get(0).get("environment")
                .stringValue()).isEqualTo("DEV");
        JsonHttp.Response skipping = promote("editor", plugin, 1, "PROD", "CHG-1");
        assertThat(check(skipping.json(), "SOURCE_ACTIVE").get("ok").booleanValue()).isFalse();
        assertThat(get("viewer", "/promotions?subjectType=PLUGIN").json().get("totalItems").longValue()).isEqualTo(1);

        JsonNode route = post("editor", "/promotions", """
                {"subjectType": "ROUTE", "subjectId": %d, "version": 1, "targetEnv": "DEV", "action": "DEPLOY"}
                """.formatted(routeId)).json();
        assertThat(route.get("status").stringValue()).as(route.toString()).isEqualTo("APPLIED");

        // previews and tests apply the plugins the subtask gets: the GLOBAL one (latest version, not in DEV yet)
        // and the one it chose
        String trial = """
                {"projectId": %d, "environment": "DEV", "httpMethod": "POST", "pathTemplate": "/orders/{id}/notes",
                 "definition": %s, "sample": {"siparisNo": 42, "notMetni": "x"}}
                """.formatted(projectId, chosen.formatted("\"request-time\""));
        JsonNode preview = post("editor", "/routes/preview", trial).json();
        assertThat(preview.get("plugins").valueStream().map(applied -> applied.get("name").stringValue()))
                .containsExactly("everywhere", "request-time");
        assertThat(preview.get("request").get("headers").has("X-Request-Time")).isTrue();
        assertThat(preview.get("request").get("headers").has("X-Trace-Id")).isTrue();
        assertStatus(post("editor", "/projects/" + projectId + "/test", trial), 200);
        JsonNode testCall = ENVIRONMENTS.get("DEV").testCalls.getLast();
        assertThat(testCall.get("plugins").valueStream().map(ref -> ref.get("name").stringValue() + " v"
                + ref.get("version").intValue())).containsExactly("everywhere v1", "request-time v1");
    }
}
