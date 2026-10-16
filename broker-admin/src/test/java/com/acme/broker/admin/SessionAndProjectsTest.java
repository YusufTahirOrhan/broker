package com.acme.broker.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.acme.broker.admin.support.AdminTestBase;
import com.acme.broker.core.testing.JsonHttp;

import tools.jackson.databind.JsonNode;

class SessionAndProjectsTest extends AdminTestBase {

    @Test
    void sessionEnvironmentsAndRules() {
        JsonNode me = get("lead", "/me").json();
        assertThat(me.get("username").stringValue()).isEqualTo("lead");
        assertThat(me.get("roles").valueStream().map(JsonNode::stringValue))
                .containsExactly("VIEWER", "ROUTE_EDITOR", "APPROVER");
        assertThat(get("editor", "/me").json().get("roles").valueStream().map(JsonNode::stringValue))
                .containsExactly("VIEWER", "ROUTE_EDITOR");

        JsonNode environments = get("viewer", "/environments").json();
        assertThat(environments.valueStream().map(environment -> environment.get("code").stringValue()))
                .containsExactly("DEV", "TEST", "PROD");
        assertThat(environments.get(2).get("requiresApproval").booleanValue()).isTrue();
        assertThat(environments.get(2).get("testCallsAllowed").booleanValue()).isFalse();
        assertThat(environments.get(0).has("adminApiUrl")).isFalse();

        JsonNode rules = get("viewer", "/meta/definition-rules").json();
        assertThat(rules.get("patterns").get("subtask").stringValue()).isEqualTo("^[a-z][a-z0-9]*(_[a-z0-9]+)*$");
        assertThat(rules.get("readableHeaders").size()).isEqualTo(3);
        assertThat(rules.get("forbiddenHeaderExceptions").valueStream().map(JsonNode::stringValue))
                .containsExactly("X-User-Agent");
        assertThat(rules.get("placeholders").get("definition").valueStream().map(JsonNode::stringValue))
                .contains("${request.uid}", "${request.application}", "${request.maintask}", "${request.subtask}");
    }

    @Test
    void authenticationAndRoleErrorsUseTheProblemFormat() {
        JsonHttp.Response anonymous = http.get("/api/v1/me");
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(anonymous.header("Content-Type")).startsWith("application/problem+json");
        assertThat(anonymous.json().get("code").stringValue()).isEqualTo("UNAUTHENTICATED");

        JsonHttp.Response wrongPassword = http.send("GET", "/api/v1/me", null,
                java.util.Map.of("Authorization", "Basic dmlld2VyOm5vcGU="));
        assertThat(wrongPassword.status()).isEqualTo(401);

        JsonHttp.Response viewer = post("viewer", "/projects", """
                {"externalProjectId": "P-100", "maintask": "order-service", "permissionPrefix": "ORDER"}
                """);
        assertThat(viewer.status()).isEqualTo(403);
        assertThat(viewer.json().get("code").stringValue()).isEqualTo("FORBIDDEN");
    }

    @Test
    void projectsAreRegisteredWithImmutableKeys() {
        JsonHttp.Response created = post("editor", "/projects", """
                {"externalProjectId": "P-100", "maintask": "order-service", "permissionPrefix": "ORDER",
                 "ownerTeam": "siparis"}
                """);
        assertStatus(created, 201);
        JsonNode project = created.json();
        assertThat(project.get("name").stringValue()).isEqualTo("Sipariş Servisi");
        assertThat(project.get("catalogStatus").stringValue()).isEqualTo("LINKED");
        assertThat(project.get("createdBy").stringValue()).isEqualTo("editor");
        long id = project.get("id").longValue();

        JsonNode catalog = get("viewer", "/catalog/projects?query=sipari").json();
        assertThat(catalog.get(0).get("registeredProjectId").longValue()).isEqualTo(id);
        assertThat(get("viewer", "/catalog/projects?query=crm").json().get(0).get("registeredProjectId").isNull())
                .isTrue();

        JsonHttp.Response sameName = post("editor", "/projects", """
                {"externalProjectId": "P-300", "maintask": "ORDER-SERVICE", "permissionPrefix": "CRM"}
                """);
        assertThat(sameName.status()).isEqualTo(409);
        assertThat(sameName.json().get("code").stringValue()).isEqualTo("DUPLICATE");

        JsonHttp.Response overlap = post("editor", "/projects", """
                {"externalProjectId": "P-300", "maintask": "crm", "permissionPrefix": "ORDER_CRM"}
                """);
        assertThat(overlap.status()).isEqualTo(409);
        assertThat(overlap.json().get("code").stringValue()).isEqualTo("PREFIX_OVERLAP");

        JsonHttp.Response invalid = post("editor", "/projects", """
                {"externalProjectId": "P-999", "maintask": "-bad", "permissionPrefix": "lower"}
                """);
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.json().get("errors").valueStream().map(error -> error.get("field").stringValue()))
                .contains("maintask", "permissionPrefix", "externalProjectId");

        JsonHttp.Response renamed = as("editor", "PATCH", "/projects/" + id, """
                {"name": "Sipariş", "description": "Siparişler", "maintask": "orders"}
                """);
        assertThat(renamed.status()).isEqualTo(400);
        assertThat(renamed.json().get("errors").get(0).get("field").stringValue()).isEqualTo("maintask");
        JsonNode patched = as("editor", "PATCH", "/projects/" + id, "{\"name\": \"Sipariş\", \"description\": \"x\"}").json();
        assertThat(patched.get("name").stringValue()).isEqualTo("Sipariş");
        assertThat(patched.get("ownerTeam").stringValue()).isEqualTo("siparis");

        JsonNode unlinked = post("editor", "/projects", """
                {"maintask": "legacy-hr", "name": "Eski İK", "permissionPrefix": "HR"}
                """).json();
        assertThat(unlinked.get("catalogStatus").stringValue()).isEqualTo("UNLINKED");
        JsonNode linked = as("editor", "PATCH", "/projects/" + unlinked.get("id").longValue(),
                "{\"externalProjectId\": \"P-400\"}").json();
        assertThat(linked.get("catalogStatus").stringValue()).isEqualTo("LINKED");
        assertThat(get("viewer", "/projects?catalogStatus=LINKED").json().size()).isEqualTo(2);
    }

    @Test
    void environmentSettingsReachTheEnvironmentFirst() {
        long id = post("editor", "/projects", """
                {"externalProjectId": "P-100", "maintask": "order-service", "permissionPrefix": "ORDER"}
                """).json().get("id").longValue();

        JsonHttp.Response dev = put("editor", "/projects/" + id + "/environments/DEV", """
                {"baseUrl": "http://order-service.dev.svc:8080", "enabled": true}
                """);
        assertStatus(dev, 200);
        assertThat(dev.json().get("specPath").stringValue()).isEqualTo("/v3/api-docs");
        assertThat(dev.json().get("configVersion").longValue()).isEqualTo(1);
        assertThat(ENVIRONMENTS.get("DEV").projects.get("order-service").get("baseUrl").stringValue())
                .isEqualTo("http://order-service.dev.svc:8080");

        JsonHttp.Response prodByEditor = put("editor", "/projects/" + id + "/environments/PROD", """
                {"baseUrl": "http://order-service.prod.svc:8080"}
                """);
        assertThat(prodByEditor.status()).isEqualTo(403);
        assertStatus(put("approver", "/projects/" + id + "/environments/PROD", """
                {"baseUrl": "http://order-service.prod.svc:8080"}
                """), 200);

        jdbc.sql("UPDATE environment SET admin_api_url = 'http://127.0.0.1:1' WHERE code = 'TEST'").update();
        try {
            JsonHttp.Response unreachable = put("editor", "/projects/" + id + "/environments/TEST", """
                    {"baseUrl": "http://order-service.test.svc:8080"}
                    """);
            assertThat(unreachable.status()).isEqualTo(502);
            assertThat(unreachable.json().get("code").stringValue()).isEqualTo("ENV_UNREACHABLE");
        } finally {
            jdbc.sql("UPDATE environment SET admin_api_url = :url WHERE code = 'TEST'")
                    .param("url", ENVIRONMENTS.url("test")).update();
        }
        JsonNode project = get("viewer", "/projects/" + id).json();
        assertThat(project.get("environments").valueStream().map(env -> env.get("environment").stringValue()))
                .containsExactly("DEV", "PROD");

        JsonHttp.Response badUrl = put("editor", "/projects/" + id + "/environments/DEV", "{\"baseUrl\": \"ftp://x\"}");
        assertThat(badUrl.status()).isEqualTo(400);
    }

    @Test
    void permissionsComeFromTheAuthorizationService() {
        long id = post("editor", "/projects", """
                {"externalProjectId": "P-200", "maintask": "logProject", "permissionPrefix": "LOGPROJECT"}
                """).json().get("id").longValue();

        JsonHttp.Response created = post("editor", "/projects/" + id + "/permissions", """
                {"suffix": "moderator", "description": "Moderatör"}
                """);
        assertStatus(created, 201);
        assertThat(created.json().get("code").stringValue()).isEqualTo("LOGPROJECT_MODERATOR");
        assertThat(created.json().get("environments").size()).isEqualTo(3);
        assertThat(post("editor", "/projects/" + id + "/permissions", "{\"suffix\": \"MODERATOR\"}").status())
                .isEqualTo(409);
        assertThat(post("editor", "/projects/" + id + "/permissions", "{\"suffix\": \"BAD-SUFFIX\"}").status())
                .isEqualTo(400);

        JsonNode dev = get("viewer", "/projects/" + id + "/permissions?env=DEV").json();
        assertThat(dev.valueStream().map(permission -> permission.get("code").stringValue()))
                .containsExactly("LOGPROJECT_MODERATOR", "LOGPROJECT_TEMEL");
        assertThat(dev.get(0).get("suffix").stringValue()).isEqualTo("MODERATOR");
        assertThat(dev.get(0).get("routeCount").longValue()).isZero();
        assertThat(get("viewer", "/projects/" + id + "/permissions?env=PROD").json().size()).isEqualTo(1);
    }

    @Test
    void openApiDocumentIsServed() {
        JsonHttp.Response docs = http.get("/v3/api-docs");
        assertThat(docs.status()).isEqualTo(200);
        assertThat(docs.json().get("paths").has("/api/v1/promotions")).isTrue();
    }
}
