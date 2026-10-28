package com.acme.yapi.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.acme.yapi.admin.support.AdminTestBase;
import com.acme.yapi.core.testing.JsonHttp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class PromotionFlowTest extends AdminTestBase {

    private JsonHttp.Response promote(String user, String type, long subject, Integer version, String target,
                                      String action, String reason) {
        return post(user, "/promotions", """
                {"subjectType": "%s", "subjectId": %d, "version": %s, "targetEnv": "%s", "action": "%s", "reason": %s}
                """.formatted(type, subject, version == null ? "null" : version.toString(), target, action,
                reason == null ? "null" : "\"" + reason + "\""));
    }

    private JsonNode applied(JsonHttp.Response response) {
        assertStatus(response, 201);
        assertThat(response.json().get("status").stringValue()).as(response.text()).isEqualTo("APPLIED");
        return response.json();
    }

    @Test
    void aVersionTravelsDevTestProdWithApprovalAtTheEnd() {
        project("crm", "CRM", "P-300");
        long projectId = project("order-service", "ORDER", "P-100");
        long profileId = profile("order-service");
        long routeId = route(projectId, addOrderNote("add_order_note", profileId));

        JsonHttp.Response noProfile = promote("editor", "ROUTE", routeId, 1, "DEV", "DEPLOY", null);
        assertThat(noProfile.status()).isEqualTo(422);
        assertThat(noProfile.json().get("code").stringValue()).isEqualTo("PRECHECK_FAILED");
        assertThat(check(noProfile.json(), "PROFILE_ACTIVE").get("ok").booleanValue()).isFalse();
        assertThat(check(noProfile.json(), "NO_OPEN_REQUEST").get("ok").booleanValue()).isTrue();

        for (String environment : new String[]{"DEV", "PREPROD"}) {
            applied(promote("editor", "PROFILE", profileId, 1, environment, "DEPLOY", null));
        }
        JsonNode dev = applied(promote("editor", "ROUTE", routeId, 1, "DEV", "DEPLOY", null));
        assertThat(dev.get("sourceEnv").isNull()).isTrue();
        assertThat(dev.get("requiresApproval").booleanValue()).isFalse();
        assertThat(dev.get("probeStatus").stringValue()).isEqualTo("OK");
        assertThat(dev.get("configVersion").longValue()).isPositive();
        ObjectNode sent = ENVIRONMENTS.get("DEV").routes.get("order-service/add_order_note");
        assertThat(sent.get("version").intValue()).isEqualTo(1);
        assertThat(sent.get("promotionId").longValue()).isEqualTo(dev.get("id").longValue());
        assertThat(sent.get("responseProfile").stringValue()).isEqualTo("order-service");
        assertThat(sent.get("permissions").valueStream().map(JsonNode::stringValue))
                .containsExactly("ORDER_WRITE", "CRM_AGENT");
        assertThat(sent.get("actor").stringValue()).isEqualTo("editor");

        JsonNode skipping = promote("editor", "ROUTE", routeId, 1, "PROD", "DEPLOY", "CHG-1").json();
        assertThat(check(skipping, "SOURCE_ACTIVE").get("ok").booleanValue()).isFalse();
        assertThat(check(skipping, "SOURCE_ACTIVE").get("message").stringValue())
                .isEqualTo("v1 PREPROD ortamında aktif değil; yalnızca bir önceki ortamda aktif versiyon taşınır");
        assertThat(check(skipping, "PROFILE_ACTIVE").get("ok").booleanValue()).isFalse();

        applied(promote("editor", "ROUTE", routeId, 1, "PREPROD", "DEPLOY", null));
        JsonNode profileRequest = promote("lead", "PROFILE", profileId, 1, "PROD", "DEPLOY", "CHG-0").json();
        assertThat(profileRequest.get("status").stringValue()).isEqualTo("PENDING_APPROVAL");
        assertThat(post("approver", "/promotions/" + profileRequest.get("id").longValue() + "/approve", "{}").json()
                .get("status").stringValue()).isEqualTo("APPLIED");
        JsonHttp.Response noReason = promote("lead", "ROUTE", routeId, 1, "PROD", "DEPLOY", null);
        assertThat(noReason.status()).isEqualTo(400);
        assertThat(noReason.json().get("errors").get(0).get("field").stringValue()).isEqualTo("reason");

        JsonHttp.Response opened = promote("lead", "ROUTE", routeId, 1, "PROD", "DEPLOY", "CHG-1234");
        assertStatus(opened, 201);
        JsonNode pending = opened.json();
        long requestId = pending.get("id").longValue();
        assertThat(pending.get("status").stringValue()).isEqualTo("PENDING_APPROVAL");
        assertThat(pending.get("requiresApproval").booleanValue()).isTrue();
        assertThat(pending.get("sourceEnv").stringValue()).isEqualTo("PREPROD");
        assertThat(pending.get("subjectName").stringValue()).isEqualTo("order-service / add_order_note");
        assertThat(pending.get("diff").get("deployedVersion").isNull()).isTrue();
        assertThat(pending.get("diff").get("foreignPermissionsAdded").get(0).stringValue()).isEqualTo("CRM_AGENT");
        assertThat(pending.get("allowedActions").valueStream().map(JsonNode::stringValue)).containsExactly("CANCEL");
        assertThat(get("approver", "/promotions/" + requestId).json().get("allowedActions").valueStream()
                .map(JsonNode::stringValue)).containsExactly("APPROVE", "REJECT");

        JsonHttp.Response duplicate = promote("editor", "ROUTE", routeId, 1, "PROD", "DEPLOY", "CHG-2");
        assertThat(check(duplicate.json(), "NO_OPEN_REQUEST").get("ok").booleanValue()).isFalse();

        JsonHttp.Response ownApproval = post("lead", "/promotions/" + requestId + "/approve", "{}");
        assertThat(ownApproval.status()).isEqualTo(403);
        assertThat(ownApproval.json().get("code").stringValue()).isEqualTo("FOUR_EYES");
        assertThat(post("editor", "/promotions/" + requestId + "/approve", "{}").status()).isEqualTo(403);
        assertThat(ENVIRONMENTS.get("PROD").routes).isEmpty();

        JsonNode approved = post("approver", "/promotions/" + requestId + "/approve", "{\"note\": \"uygun\"}").json();
        assertThat(approved.get("status").stringValue()).isEqualTo("APPLIED");
        assertThat(approved.get("decidedBy").stringValue()).isEqualTo("approver");
        assertThat(approved.get("decisionNote").stringValue()).isEqualTo("uygun");
        assertThat(approved.get("appliedAt").isNull()).isFalse();
        assertThat(ENVIRONMENTS.get("PROD").routes.get("order-service/add_order_note").get("actor").stringValue())
                .isEqualTo("approver");
        assertThat(post("approver", "/promotions/" + requestId + "/approve", "{}").json().get("code").stringValue())
                .isEqualTo("INVALID_STATE");

        JsonNode deployments = get("viewer", "/routes/" + routeId).json().get("deployments");
        assertThat(deployments.valueStream().map(d -> d.get("environment").stringValue() + " v" + d.get("version").intValue()
                + " " + d.get("status").stringValue())).containsExactly("DEV v1 ACTIVE", "PREPROD v1 ACTIVE", "PROD v1 ACTIVE");
        assertThat(get("viewer", "/routes/" + routeId + "/history").json().get(0).get("activeIn").size()).isEqualTo(3);
        JsonHttp.Response archive = post("editor", "/routes/" + routeId + "/archive", null);
        assertThat(archive.status()).isEqualTo(409);
        assertThat(archive.json().get("code").stringValue()).isEqualTo("ROUTE_DEPLOYED");

        JsonNode affected = get("viewer", "/response-profiles/" + profileId).json().get("usage");
        assertThat(affected.size()).isEqualTo(3);

        JsonNode page = get("viewer", "/promotions?targetEnv=PROD&subjectType=ROUTE&page=0&size=1").json();
        assertThat(page.get("totalItems").longValue()).isEqualTo(1);
        assertThat(page.get("items").get(0).has("diff")).isFalse();
        assertThat(page.get("items").get(0).has("affectedRoutes")).isFalse();
        assertThat(page.get("items").get(0).has("allowedActions")).isTrue();
        assertThat(get("viewer", "/promotions?status=APPLIED&mine=true").json().get("totalItems").longValue()).isZero();
        assertThat(get("editor", "/promotions?status=APPLIED,FAILED&mine=true&projectId=" + projectId).json()
                .get("totalItems").longValue()).isEqualTo(2);
    }

    private static JsonNode check(JsonNode response, String name) {
        return response.get("checks").valueStream().filter(check -> name.equals(check.get("check").stringValue()))
                .findFirst().orElseThrow(() -> new AssertionError(name + " yok: " + response));
    }

    @Test
    void openingAnonymousAccessNeedsApprovalEverywhere() {
        long projectId = project("order-service", "ORDER", "P-100");
        long routeId = route(projectId, """
                {"subtask": "public_status", "httpMethod": "GET", "pathTemplate": "/status", "anonymous": true,
                 "definition": {}}
                """);

        JsonHttp.Response withoutReason = promote("editor", "ROUTE", routeId, 1, "DEV", "DEPLOY", null);
        assertThat(withoutReason.status()).isEqualTo(400);
        JsonNode pending = promote("editor", "ROUTE", routeId, 1, "DEV", "DEPLOY", "Herkese açık durum sayfası").json();
        assertThat(pending.get("status").stringValue()).isEqualTo("PENDING_APPROVAL");
        assertThat(pending.get("diff").get("opensAnonymous").booleanValue()).isTrue();

        JsonHttp.Response rejectWithoutNote = post("approver", "/promotions/" + pending.get("id").longValue() + "/reject", "{}");
        assertThat(rejectWithoutNote.status()).isEqualTo(400);
        JsonNode rejected = post("approver", "/promotions/" + pending.get("id").longValue() + "/reject",
                "{\"note\": \"gerekçe yetersiz\"}").json();
        assertThat(rejected.get("status").stringValue()).isEqualTo("REJECTED");

        JsonNode again = promote("editor", "ROUTE", routeId, 1, "DEV", "DEPLOY", "Tekrar").json();
        JsonHttp.Response someoneElse = post("lead", "/promotions/" + again.get("id").longValue() + "/cancel", null);
        assertThat(someoneElse.status()).isEqualTo(403);
        assertThat(post("editor", "/promotions/" + again.get("id").longValue() + "/cancel", null).json()
                .get("status").stringValue()).isEqualTo("CANCELLED");
    }

    @Test
    void aFailedProbeChangesNothingAndCanBeRetried() {
        long projectId = project("order-service", "ORDER", "P-100");
        long routeId = route(projectId, """
                {"subtask": "get_order", "httpMethod": "GET", "pathTemplate": "/orders/{id}",
                 "permissions": ["ORDER_READ"],
                 "definition": {"request": {"fields": [{"name": "orderId", "type": "integer", "required": true}],
                                            "pathVariables": {"id": "${request.orderId}"}}}}
                """);
        ENVIRONMENTS.get("DEV").probeResults.put("GET /orders/{id}", "PATH_NOT_FOUND");

        JsonNode failed = promote("editor", "ROUTE", routeId, 1, "DEV", "DEPLOY", null).json();
        assertThat(failed.get("status").stringValue()).isEqualTo("FAILED");
        assertThat(failed.get("probeStatus").stringValue()).isEqualTo("PATH_NOT_FOUND");
        assertThat(failed.get("failureReason").stringValue()).startsWith("Yoklama başarısız: PATH_NOT_FOUND");
        assertThat(failed.get("allowedActions").valueStream().map(JsonNode::stringValue)).containsExactly("RETRY");
        assertThat(ENVIRONMENTS.get("DEV").routes).isEmpty();

        ENVIRONMENTS.get("DEV").probeResults.clear();
        JsonNode retried = post("editor", "/promotions/" + failed.get("id").longValue() + "/retry", null).json();
        assertThat(retried.get("status").stringValue()).isEqualTo("APPLIED");
        assertThat(retried.get("failureReason").isNull()).isTrue();
    }

    @Test
    void aLostAnswerLeavesTheRequestApplyingUntilRetried() {
        long projectId = project("order-service", "ORDER", "P-100");
        long routeId = route(projectId, """
                {"subtask": "get_order", "httpMethod": "GET", "pathTemplate": "/orders", "permissions": ["ORDER_READ"],
                 "definition": {}}
                """);
        ENVIRONMENTS.get("DEV").silent = true;

        JsonNode unanswered = promote("editor", "ROUTE", routeId, 1, "DEV", "DEPLOY", null).json();
        assertThat(unanswered.get("status").stringValue()).isEqualTo("APPLYING");
        assertThat(unanswered.get("failureReason").stringValue()).contains("yeniden denenebilir");
        assertThat(promote("editor", "ROUTE", routeId, 1, "DEV", "DEPLOY", null).status()).isEqualTo(422);

        ENVIRONMENTS.get("DEV").silent = false;
        JsonNode retried = post("editor", "/promotions/" + unanswered.get("id").longValue() + "/retry", null).json();
        assertThat(retried.get("status").stringValue()).isEqualTo("APPLIED");
    }

    @Test
    void killSwitchEnableAndRollback() {
        long projectId = project("order-service", "ORDER", "P-100");
        long routeId = route(projectId, """
                {"subtask": "get_order", "httpMethod": "GET", "pathTemplate": "/orders", "permissions": ["ORDER_READ"],
                 "definition": {"timeoutMs": 1000}}
                """);
        applied(promote("editor", "ROUTE", routeId, 1, "DEV", "DEPLOY", null));

        JsonHttp.Response notDeployed = promote("editor", "ROUTE", routeId, null, "PREPROD", "DISABLE", null);
        assertThat(notDeployed.status()).isEqualTo(422);
        assertThat(check(notDeployed.json(), "DEPLOYED_IN_TARGET").get("message").stringValue())
                .isEqualTo("Subtask PREPROD ortamında yok");
        JsonHttp.Response notDisabled = promote("editor", "ROUTE", routeId, null, "DEV", "ENABLE", null);
        assertThat(check(notDisabled.json(), "DEPLOYED_IN_TARGET").get("ok").booleanValue()).isFalse();
        JsonNode disabled = applied(promote("editor", "ROUTE", routeId, null, "DEV", "DISABLE", null));
        assertThat(disabled.get("version").intValue()).isEqualTo(1);
        assertThat(ENVIRONMENTS.get("DEV").routes.get("order-service/get_order").get("status").stringValue())
                .isEqualTo("DISABLED");
        assertThat(get("viewer", "/routes/" + routeId).json().get("deployments").get(0).get("status").stringValue())
                .isEqualTo("DISABLED");
        applied(promote("editor", "ROUTE", routeId, null, "DEV", "ENABLE", null));
        assertThat(ENVIRONMENTS.get("DEV").routes.get("order-service/get_order").get("status").stringValue())
                .isEqualTo("ACTIVE");

        put("editor", "/routes/" + routeId, """
                {"httpMethod": "GET", "pathTemplate": "/orders", "permissions": ["ORDER_READ"],
                 "definition": {"timeoutMs": 2000}, "version": 1}
                """);
        applied(promote("editor", "ROUTE", routeId, 2, "DEV", "DEPLOY", null));
        assertThat(promote("editor", "ROUTE", routeId, 2, "PREPROD", "ROLLBACK", null).json().get("checks").toString())
                .contains("PREVIOUSLY_APPLIED");
        JsonNode rolledBack = applied(promote("editor", "ROUTE", routeId, 1, "DEV", "ROLLBACK", null));
        assertThat(rolledBack.get("action").stringValue()).isEqualTo("ROLLBACK");
        assertThat(ENVIRONMENTS.get("DEV").routes.get("order-service/get_order").get("version").intValue()).isEqualTo(1);
        assertThat(ENVIRONMENTS.get("DEV").routes.get("order-service/get_order").get("definition").get("timeoutMs")
                .intValue()).isEqualTo(1000);
    }

    @Test
    void permissionsMustExistInTheTarget() {
        long projectId = project("log-project", "LOGPROJECT", "P-200");
        long routeId = route(projectId, """
                {"subtask": "logs", "httpMethod": "GET", "pathTemplate": "/logs", "permissions": ["LOGPROJECT_TEMEL"],
                 "definition": {}}
                """);
        applied(promote("editor", "ROUTE", routeId, 1, "DEV", "DEPLOY", null));
        applied(promote("editor", "ROUTE", routeId, 1, "PREPROD", "DEPLOY", null));

        JsonHttp.Response prod = promote("lead", "ROUTE", routeId, 1, "PROD", "DEPLOY", "CHG-9");
        assertThat(prod.status()).isEqualTo(422);
        assertThat(check(prod.json(), "PERMISSIONS_EXIST").get("message").stringValue())
                .isEqualTo("PROD Koordinatör'ünde yok: LOGPROJECT_TEMEL");
        assertThat(get("viewer", "/promotions?targetEnv=PROD").json().get("totalItems").longValue()).isZero();
    }

    @Test
    void subtasksWithAnAlarmCheckNeedTheirAlarmRouteActiveInTheTarget() {
        project("crm", "CRM", "P-300");
        long security = project("guvenlik", "GUVENLIK", "P-400");
        long alarmId = route(security, """
                {"subtask": "alarm_kontrol", "httpMethod": "POST", "pathTemplate": "/alarm/check", "permissions": [], "foreignPermissions": ["CRM_AGENT"],
                 "definition": {"alarmService": {"resultPath": "alarms"},
                                "request": {"body": {"mode": "TEMPLATE", "contentType": "application/json",
                                                     "template": {"values": "${alarm.values}"}}}}}
                """);
        long projectId = project("kredi", "KREDI", "P-100");
        long query = route(projectId, """
                {"subtask": "sorgu", "httpMethod": "POST", "pathTemplate": "/sorgu", "permissions": [], "foreignPermissions": ["CRM_AGENT"], "definition": {}}
                """);
        long own = route(projectId, """
                {"subtask": "ozel", "httpMethod": "POST", "pathTemplate": "/ozel", "permissions": [], "foreignPermissions": ["CRM_AGENT"],
                 "definition": {"alarm": {"check": "ROUTE", "maintask": "guvenlik", "subtask": "alarm_kontrol"}}}
                """);
        long off = route(projectId, """
                {"subtask": "kapali", "httpMethod": "POST", "pathTemplate": "/kapali", "permissions": [], "foreignPermissions": ["CRM_AGENT"],
                 "definition": {"alarm": {"check": "OFF"}}}
                """);
        String settings = """
                {"baseUrl": "http://kredi.dev.svc:8080", "enabled": true, "alarmRoute": %s}
                """;

        JsonHttp.Response notService = put("lead", "/projects/" + projectId + "/environments/DEV",
                settings.formatted("{\"maintask\": \"kredi\", \"subtask\": \"sorgu\"}"));
        assertStatus(notService, 400);
        assertThat(notService.json().get("errors").get(0).get("message").stringValue())
                .isEqualTo("Alarm servisi route'u değil: kredi/sorgu");
        assertStatus(put("lead", "/projects/" + projectId + "/environments/DEV",
                settings.formatted("{\"maintask\": \"guvenlik\", \"subtask\": \"yok\"}")), 400);
        JsonHttp.Response set = put("lead", "/projects/" + projectId + "/environments/DEV",
                settings.formatted("{\"maintask\": \"Guvenlik\", \"subtask\": \"alarm_kontrol\"}"));
        assertStatus(set, 200);
        assertThat(set.json().get("alarmRoute").get("subtask").stringValue()).isEqualTo("alarm_kontrol");
        assertThat(ENVIRONMENTS.get("DEV").projects.get("kredi").get("permissionPrefix").stringValue())
                .isEqualTo("KREDI");
        assertThat(ENVIRONMENTS.get("DEV").projects.get("kredi").get("alarmRoute").get("maintask").stringValue())
                .isEqualTo("guvenlik");
        assertThat(get("viewer", "/projects/" + projectId).json().toString()).contains("\"alarmRoute\"");

        JsonHttp.Response inactive = promote("editor", "ROUTE", query, 1, "DEV", "DEPLOY", null);
        assertThat(inactive.status()).as(inactive.text()).isEqualTo(422);
        assertThat(check(inactive.json(), "ALARM_ROUTE_ACTIVE").get("message").stringValue())
                .isEqualTo("Alarm route'u guvenlik/alarm_kontrol DEV ortamında aktif değil; önce o taşınmalı");
        applied(promote("editor", "ROUTE", off, 1, "DEV", "DEPLOY", null));

        applied(promote("editor", "ROUTE", alarmId, 1, "DEV", "DEPLOY", null));
        applied(promote("editor", "ROUTE", query, 1, "DEV", "DEPLOY", null));
        applied(promote("editor", "ROUTE", own, 1, "DEV", "DEPLOY", null));

        // PREPROD has no alarm route for the project, so inherited checks are off there; the named route is not
        applied(promote("editor", "ROUTE", query, 1, "PREPROD", "DEPLOY", null));
        JsonHttp.Response named = promote("editor", "ROUTE", own, 1, "PREPROD", "DEPLOY", null);
        assertThat(named.status()).isEqualTo(422);
        assertThat(check(named.json(), "ALARM_ROUTE_ACTIVE").get("ok").booleanValue()).isFalse();
    }

    @Test
    void aNullAlarmServiceIsNotAnAlarmService() {
        project("crm", "CRM", "P-300");
        long security = project("guvenlik", "GUVENLIK", "P-400");
        long notService = route(security, """
                {"subtask": "kontrol", "httpMethod": "POST", "pathTemplate": "/kontrol", "permissions": [],
                 "foreignPermissions": ["CRM_AGENT"], "definition": {"alarmService": null}}
                """);
        long projectId = project("kredi", "KREDI", "P-100");
        long named = route(projectId, """
                {"subtask": "ozel", "httpMethod": "POST", "pathTemplate": "/ozel", "permissions": [],
                 "foreignPermissions": ["CRM_AGENT"],
                 "definition": {"alarm": {"check": "ROUTE", "maintask": "guvenlik", "subtask": "kontrol"}}}
                """);

        JsonHttp.Response setting = put("lead", "/projects/" + projectId + "/environments/DEV", """
                {"baseUrl": "http://kredi.dev.svc:8080", "enabled": true,
                 "alarmRoute": {"maintask": "guvenlik", "subtask": "kontrol"}}
                """);
        assertStatus(setting, 400);
        assertThat(setting.json().get("errors").get(0).get("message").stringValue())
                .isEqualTo("Alarm servisi route'u değil: guvenlik/kontrol");

        applied(promote("editor", "ROUTE", notService, 1, "DEV", "DEPLOY", null));
        JsonHttp.Response blocked = promote("editor", "ROUTE", named, 1, "DEV", "DEPLOY", null);
        assertThat(blocked.status()).as(blocked.text()).isEqualTo(422);
        JsonNode alarm = check(blocked.json(), "ALARM_ROUTE_ACTIVE");
        assertThat(alarm.get("ok").booleanValue()).isFalse();
        assertThat(alarm.get("message").stringValue())
                .isEqualTo("Alarm route'u guvenlik/kontrol DEV ortamındaki versiyonunda alarm servisi değil");
    }
}
