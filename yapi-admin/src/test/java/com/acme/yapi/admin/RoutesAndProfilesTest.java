package com.acme.yapi.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.acme.yapi.admin.support.AdminTestBase;
import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.testing.CoreFixtures;
import com.acme.yapi.core.testing.JsonHttp;

import tools.jackson.databind.JsonNode;

class RoutesAndProfilesTest extends AdminTestBase {

    @Test
    void aSubtaskIsVersionedAndAudited() {
        project("crm", "CRM", "P-300");
        long projectId = project("order-service", "ORDER", "P-100");
        long profileId = profile("order-service");
        long id = route(projectId, addOrderNote("add_order_note", profileId));

        JsonNode route = get("viewer", "/routes/" + id).json();
        assertThat(route.get("version").intValue()).isEqualTo(1);
        assertThat(route.get("maintask").stringValue()).isEqualTo("order-service");
        assertThat(route.get("responseProfile").get("name").stringValue()).isEqualTo("order-service");
        assertThat(route.get("foreignPermissions").get(0).stringValue()).isEqualTo("CRM_AGENT");
        assertThat(route.get("deployments").size()).isZero();

        JsonHttp.Response duplicate = post("editor", "/projects/" + projectId + "/routes",
                addOrderNote("add_order_note", null).replace("\"add_order_note\"", "\"ADD_ORDER_NOTE\""));
        assertThat(duplicate.status()).isEqualTo(400); // snake_case is enforced before uniqueness
        JsonHttp.Response same = post("editor", "/projects/" + projectId + "/routes", addOrderNote("add_order_note", null));
        assertThat(same.status()).isEqualTo(409);
        assertThat(same.json().get("code").stringValue()).isEqualTo("DUPLICATE");

        String changed = addOrderNote("add_order_note", profileId)
                .replace("\"timeoutMs\": 5000", "\"timeoutMs\": 8000")
                .replace("\"subtask\": \"add_order_note\",", "\"version\": 1,");
        JsonHttp.Response updated = put("editor", "/routes/" + id, changed);
        assertStatus(updated, 200);
        assertThat(updated.json().get("version").intValue()).isEqualTo(2);
        JsonHttp.Response stale = put("editor", "/routes/" + id, changed);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.json().get("code").stringValue()).isEqualTo("VERSION_CONFLICT");
        assertThat(stale.json().get("currentVersion").intValue()).isEqualTo(2);

        JsonNode comparison = get("viewer", "/routes/" + id + "/compare?from=1&to=2").json();
        assertThat(comparison.get("changes").size()).isEqualTo(1);
        assertThat(comparison.get("changes").get(0).get("path").stringValue()).isEqualTo("definition.timeoutMs");
        assertThat(comparison.get("changes").get(0).get("before").intValue()).isEqualTo(5000);
        assertThat(comparison.get("changes").get(0).get("after").intValue()).isEqualTo(8000);
        assertThat(comparison.get("opensAnonymous").booleanValue()).isFalse();

        JsonNode rolledBack = post("editor", "/routes/" + id + "/rollback", "{\"restoreVersion\": 1}").json();
        assertThat(rolledBack.get("version").intValue()).isEqualTo(3);
        assertThat(rolledBack.get("definition").get("timeoutMs").intValue()).isEqualTo(5000);

        JsonNode history = get("viewer", "/routes/" + id + "/history").json();
        assertThat(history.valueStream().map(entry -> entry.get("action").stringValue()))
                .containsExactly("ROLLBACK", "UPDATE", "CREATE");
        assertThat(history.get(2).get("changedBy").stringValue()).isEqualTo("editor");
        assertThat(history.get(2).get("comment").stringValue()).isEqualTo("Siparişe not ekleme");
        JsonNode version = get("viewer", "/routes/" + id + "/history/2").json();
        assertThat(version.get("snapshot").get("definition").get("timeoutMs").intValue()).isEqualTo(8000);
        assertThat(version.get("snapshot").get("httpMethod").stringValue()).isEqualTo("POST");

        JsonNode archived = post("editor", "/routes/" + id + "/archive", "{\"comment\": \"kullanılmıyor\"}").json();
        assertThat(archived.get("status").stringValue()).isEqualTo("ARCHIVED");
        assertThat(put("editor", "/routes/" + id, changed.replace("\"version\": 1", "\"version\": 4")).status())
                .isEqualTo(409);
        assertThat(post("editor", "/routes/" + id + "/restore", null).json().get("status").stringValue())
                .isEqualTo("ACTIVE");

        assertThat(get("viewer", "/projects/" + projectId + "/routes?query=note").json().size()).isEqualTo(1);
        assertThat(get("viewer", "/permissions/CRM_AGENT/routes").json().get(0).get("usage").stringValue())
                .isEqualTo("FOREIGN");
    }

    @Test
    void alarmServicesAreListedAcrossProjects() {
        project("crm", "CRM", "P-300");
        long security = project("guvenlik", "GUVENLIK", "P-400");
        long alarmId = route(security, """
                {"subtask": "alarm_kontrol", "httpMethod": "POST", "pathTemplate": "/alarm/check", "permissions": [], "foreignPermissions": ["CRM_AGENT"],
                 "definition": {"alarmService": {"resultPath": "alarms"},
                                "request": {"body": {"mode": "TEMPLATE", "contentType": "application/json",
                                                     "template": {"values": "${alarm.values}"}}}}}
                """);
        long projectId = project("kredi", "KREDI", "P-100");
        route(projectId, """
                {"subtask": "sorgu", "httpMethod": "POST", "pathTemplate": "/sorgu", "permissions": [], "foreignPermissions": ["CRM_AGENT"], "definition": {}}
                """);
        // A null alarmService is no alarm service, as the parser reads it
        route(projectId, """
                {"subtask": "bos_alarm", "httpMethod": "POST", "pathTemplate": "/bos", "permissions": [], "foreignPermissions": ["CRM_AGENT"],
                 "definition": {"alarmService": null}}
                """);
        long creditAlarm = route(projectId, """
                {"subtask": "alarm_kredi", "httpMethod": "POST", "pathTemplate": "/alarm/kredi", "permissions": [], "foreignPermissions": ["CRM_AGENT"],
                 "definition": {"alarmService": {"resultPath": "data.alarms"}}}
                """);

        java.util.Map<String, Boolean> credit = new java.util.LinkedHashMap<>();
        for (JsonNode route : get("viewer", "/projects/" + projectId + "/routes").json()) {
            credit.put(route.get("subtask").stringValue(), route.get("alarmService").booleanValue());
        }
        assertThat(credit).containsExactly(java.util.Map.entry("alarm_kredi", true),
                java.util.Map.entry("bos_alarm", false), java.util.Map.entry("sorgu", false));
        assertThat(get("viewer", "/projects/" + security + "/routes").json().get(0).get("alarmService").booleanValue())
                .isTrue();

        long archived = route(projectId, """
                {"subtask": "eski_alarm", "httpMethod": "POST", "pathTemplate": "/alarm", "permissions": [], "foreignPermissions": ["CRM_AGENT"],
                 "definition": {"alarmService": {}}}
                """);
        assertStatus(post("editor", "/routes/" + archived + "/archive", null), 200);
        JsonHttp.Response deployed = post("editor", "/promotions", """
                {"subjectType": "ROUTE", "subjectId": %d, "version": 1, "targetEnv": "DEV", "action": "DEPLOY"}
                """.formatted(alarmId));
        assertStatus(deployed, 201);
        JsonNode services = get("viewer", "/routes?alarmService=true").json();
        assertThat(services.size()).isEqualTo(2);
        assertThat(services.get(0).get("id").longValue()).isEqualTo(alarmId);
        assertThat(services.get(0).get("projectId").longValue()).isEqualTo(security);
        assertThat(services.get(0).get("maintask").stringValue()).isEqualTo("guvenlik");
        assertThat(services.get(0).get("alarmService").booleanValue()).isTrue();
        assertThat(services.get(0).get("deployments").get(0).get("environment").stringValue()).isEqualTo("DEV");
        assertThat(services.get(1).get("id").longValue()).isEqualTo(creditAlarm);
        assertThat(services.get(1).get("projectId").longValue()).isEqualTo(projectId);
        assertThat(services.get(1).get("deployments").size()).isZero();

        for (String path : java.util.List.of("/routes", "/routes?alarmService=false", "/routes?alarmService=abc")) {
            JsonHttp.Response unsupported = get("viewer", path);
            assertStatus(unsupported, 400);
            assertThat(unsupported.json().get("errors").get(0).get("field").stringValue()).isEqualTo("alarmService");
        }
    }

    @Test
    void definitionsAreValidatedWithFormPaths() {
        project("crm", "CRM", "P-300");
        long projectId = project("order-service", "ORDER", "P-100");
        String body = """
                {"projectId": %d, "subtask": "getOrder", "httpMethod": "GET", "pathTemplate": "/orders/{id}",
                 "permissions": ["CRM_AGENT"], "foreignPermissions": ["ORDER_READ"], "anonymous": false,
                 "definition": {"request": {"body": {"mode": "TEMPLATE", "template": {}}}}}
                """.formatted(projectId);

        JsonNode result = post("viewer", "/routes/validate", body).json();

        assertThat(result.get("valid").booleanValue()).isFalse();
        assertThat(result.get("errors").valueStream().map(error -> error.get("field").stringValue())).contains(
                "subtask", "definition.request.pathVariables.id", "definition.request.body.mode", "permissions[0]",
                "foreignPermissions[0]");

        JsonHttp.Response saved = post("editor", "/projects/" + projectId + "/routes", body);
        assertThat(saved.status()).isEqualTo(400);
        assertThat(saved.json().get("code").stringValue()).isEqualTo("VALIDATION_FAILED");
        assertThat(saved.json().get("detail").stringValue()).startsWith("Tanım kaydedilemedi");

        JsonHttp.Response unknownPermission = post("editor", "/projects/" + projectId + "/routes", """
                {"subtask": "get_order", "httpMethod": "GET", "pathTemplate": "/orders",
                 "permissions": ["ORDER_NOBODY"], "definition": {}}
                """);
        assertThat(unknownPermission.status()).isEqualTo(400);
        assertThat(unknownPermission.json().get("errors").get(0).get("message").stringValue())
                .startsWith("DEV Koordinatör'ünde yok");
    }

    @Test
    void previewBuildsTheBackendRequestWithoutSaving() {
        long projectId = project("order-service", "ORDER", "P-100");
        JsonHttp.Response preview = post("editor", "/routes/preview", """
                {"projectId": %d, "environment": "PREPROD", "httpMethod": "POST", "pathTemplate": "/orders/{id}/notes",
                 "definition": %s, "sample": {"siparisNo": 981, "notMetni": "Kapıya bırakın"},
                 "headers": {"Accept-Language": "tr-TR"}}
                """.formatted(projectId, CoreFixtures.ADD_ORDER_NOTE));

        assertStatus(preview, 200);
        JsonNode request = preview.json().get("request");
        assertThat(request.get("url").stringValue())
                .isEqualTo("http://order-service.preprod.svc:8080/orders/981/notes?notify=false");
        assertThat(request.get("headers").get("X-uid").stringValue()).isEqualTo("editor");
        assertThat(request.get("headers").get("Accept-Language").stringValue()).isEqualTo("tr-TR");
        assertThat(request.get("body").get("authorId").stringValue()).isEqualTo("editor");

        // the login service's route: envelope fields need no field definition, X-User-Agent is passed on
        JsonNode login = post("editor", "/routes/preview", """
                {"projectId": %d, "httpMethod": "POST", "pathTemplate": "/login",
                 "definition": {"request": {"fields": [{"name": "password", "type": "string", "required": true}],
                                            "headers": {"X-Clientip": "${header.X-Clientip}",
                                                        "X-User-Agent": "${header.X-User-Agent}"},
                                            "body": {"mode": "TEMPLATE",
                                                     "template": {"uid": "${request.uid}", "password": "${request.password}"}}}},
                 "sample": {"maintask": "giris", "subtask": "login", "uid": "u42", "password": "p4ss"},
                 "headers": {"X-Clientip": "10.0.0.1", "X-User-Agent": "agent"}}
                """.formatted(projectId)).json();
        assertThat(login.get("errors")).isEmpty();
        assertThat(login.get("request").get("body")).isEqualTo(Json.parse("{\"uid\": \"u42\", \"password\": \"p4ss\"}"));
        assertThat(login.get("request").get("headers").get("X-User-Agent").stringValue()).isEqualTo("agent");

        // ${user.permission}: the preview user holds the form's permissions, own ones first
        String permissionPreview = """
                {"projectId": %d, "httpMethod": "GET", "pathTemplate": "/sorgu",
                 "definition": {"request": {"headers": {"X-Perm": "${user.permission}"}}},
                 "sample": {}, %s}
                """;
        assertThat(post("editor", "/routes/preview", permissionPreview.formatted(projectId,
                "\"permissions\": [\"ORDER_READ\", \"ORDER_WRITE\"], \"foreignPermissions\": [\"CRM_AGENT\"]")).json()
                .at("/request/headers/X-Perm").stringValue()).isEqualTo("ORDER_READ");
        assertThat(post("editor", "/routes/preview", permissionPreview.formatted(projectId,
                "\"foreignPermissions\": [\"CRM_AGENT\"]")).json().at("/request/headers/X-Perm").stringValue())
                .isEqualTo("CRM_AGENT");
        assertThat(post("editor", "/routes/preview", permissionPreview.formatted(projectId, "\"userId\": \"x\""))
                .json().at("/request/headers").has("X-Perm")).isFalse();

        JsonNode invalid = post("editor", "/routes/preview", """
                {"projectId": %d, "httpMethod": "POST", "pathTemplate": "/orders/{id}/notes", "definition": %s,
                 "sample": {"notMetni": 5}}
                """.formatted(projectId, CoreFixtures.ADD_ORDER_NOTE)).json();
        assertThat(invalid.get("request").isNull()).isTrue();
        assertThat(invalid.get("errors").size()).isEqualTo(2);
    }

    @Test
    void profilesFollowTheSamePattern() {
        long id = profile("log-project");
        assertThat(post("editor", "/response-profiles", """
                {"name": "log-project", "definition": %s}
                """.formatted(CoreFixtures.LOG_PROJECT_PROFILE)).json().get("code").stringValue()).isEqualTo("DUPLICATE");

        JsonNode invalid = post("editor", "/response-profiles", """
                {"name": "broken", "definition": {"success": {"httpStatus": 200, "body": "${request.x}"}}}
                """).json();
        assertThat(invalid.get("errors").valueStream().map(error -> error.get("field").stringValue()))
                .contains("definition.success.body", "definition.error");

        String changed = CoreFixtures.LOG_PROJECT_PROFILE.replace("LogProject Hata!", "Hata");
        JsonHttp.Response updated = put("editor", "/response-profiles/" + id, """
                {"description": "v2", "definition": %s, "version": 1, "comment": "metin"}
                """.formatted(changed));
        assertStatus(updated, 200);
        assertThat(updated.json().get("version").intValue()).isEqualTo(2);
        assertThat(put("editor", "/response-profiles/" + id, """
                {"definition": %s, "version": 1}
                """.formatted(changed)).json().get("code").stringValue()).isEqualTo("VERSION_CONFLICT");

        JsonNode comparison = get("viewer", "/response-profiles/" + id + "/compare?from=1&to=2").json();
        assertThat(comparison.get("changes").valueStream().map(change -> change.get("path").stringValue()))
                .contains("definition.error.body.reason", "description");
        assertThat(post("editor", "/response-profiles/" + id + "/rollback", "{\"restoreVersion\": 1}").json()
                .get("version").intValue()).isEqualTo(3);
        assertThat(get("viewer", "/response-profiles/" + id + "/history").json().get(0).get("action").stringValue())
                .isEqualTo("ROLLBACK");
        assertThat(get("viewer", "/response-profiles").json().get(0).get("name").stringValue()).isEqualTo("log-project");

        JsonNode success = post("viewer", "/response-profiles/preview", """
                {"definition": %s, "status": 201, "body": {"id": 5}}
                """.formatted(CoreFixtures.LOG_PROJECT_PROFILE)).json();
        assertThat(success.get("status").intValue()).isEqualTo(200);
        assertThat(success.get("body")).isEqualTo(Json.parse("{\"BACKEND_STATUS\": 201, \"status\": 200, \"data\": {\"id\": 5}}"));
        JsonNode timeout = post("viewer", "/response-profiles/preview", """
                {"definition": %s, "status": null}
                """.formatted(CoreFixtures.LOG_PROJECT_PROFILE)).json();
        assertThat(timeout.get("outcome").stringValue()).isEqualTo("TIMEOUT");
        assertThat(timeout.get("status").intValue()).isEqualTo(400);
    }

    @Test
    void profileCasesArePreviewedAndValidatedWithFormPaths() {
        String definition = """
                {"success": {
                   "cases": [{"when": [{"value": "${backend.body.status-code}", "exists": true}],
                              "httpStatus": 200, "body": {"status": "${backend.body.status-code}",
                                                          "message": "${backend.body.message}",
                                                          "trace": "${backend.header.X-Trace}"}}],
                   "httpStatus": 200, "body": {"status": 200, "data": "${backend.body}"}},
                 "error": {"httpStatus": "${backend.status}", "body": {"status": "${backend.status}"}}}
                """;

        JsonNode matched = post("viewer", "/response-profiles/preview", """
                {"definition": %s, "status": 200, "body": {"status-code": 409}, "headers": {"x-trace": "t-1"}}
                """.formatted(definition)).json();
        assertThat(matched.get("matchedTemplate").stringValue()).isEqualTo("success");
        assertThat(matched.get("matchedCase").intValue()).isZero();
        assertThat(matched.get("body")).isEqualTo(Json.parse("{\"status\": 409, \"message\": null, \"trace\": \"t-1\"}"));

        JsonNode plain = post("viewer", "/response-profiles/preview", """
                {"definition": %s, "status": 200, "body": {"id": 1}}
                """.formatted(definition)).json();
        assertThat(plain.has("matchedCase")).isTrue();
        assertThat(plain.get("matchedCase").isNull()).isTrue();
        JsonNode timeout = post("viewer", "/response-profiles/preview", """
                {"definition": %s, "status": null}
                """.formatted(definition)).json();
        assertThat(timeout.get("matchedTemplate").stringValue()).isEqualTo("error");
        assertThat(timeout.get("status").intValue()).isEqualTo(504);

        JsonHttp.Response invalid = post("editor", "/response-profiles", """
                {"name": "broken-cases", "definition": {"success": {"httpStatus": 200, "body": {},
                   "cases": [{"when": [{"value": "${backend.body.a..b}", "like": 1}], "httpStatus": 200,
                              "body": {"x": "${requestId}"}}]},
                 "error": {"httpStatus": 500, "body": {}}}}
                """);
        assertStatus(invalid, 400);
        assertThat(invalid.json().get("errors").valueStream().map(error -> error.get("field").stringValue()))
                .containsExactlyInAnyOrder("definition.success.cases[0].when[0].like",
                        "definition.success.cases[0].when[0]", "definition.success.cases[0].when[0].value");
        JsonNode rules = get("viewer", "/meta/definition-rules").json();
        assertThat(rules.get("profile").get("operators").valueStream().map(JsonNode::stringValue))
                .containsExactly("exists", "equals", "notEquals", "in");
        assertThat(rules.get("placeholders").get("profile").valueStream().map(JsonNode::stringValue))
                .contains("${backend.body.<yol>}", "${backend.header.<ad>}");
    }
}
