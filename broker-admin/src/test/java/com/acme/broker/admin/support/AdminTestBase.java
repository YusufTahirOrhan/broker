package com.acme.broker.admin.support;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.acme.broker.core.testing.JsonHttp;

/**
 * The central admin with the {@code local} profile (HTTP Basic, in-memory project and authorization services),
 * PostgreSQL in a container and the environments faked by {@link FakeEnvironments}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "broker.admin.catalog.projects.P-100=Sipariş Servisi",
        "broker.admin.catalog.projects.P-200=Log Projesi",
        "broker.admin.catalog.projects.P-300=CRM",
        "broker.admin.catalog.projects.P-400=İnsan Kaynakları",
        "broker.admin.authorization.permissions.DEV=ORDER_READ,ORDER_WRITE,LOGPROJECT_TEMEL,CRM_AGENT",
        "broker.admin.authorization.permissions.TEST=ORDER_READ,ORDER_WRITE,LOGPROJECT_TEMEL,CRM_AGENT",
        "broker.admin.authorization.permissions.PROD=ORDER_READ,ORDER_WRITE,CRM_AGENT"
})
@ActiveProfiles("local")
public abstract class AdminTestBase {

    public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    public static final FakeEnvironments ENVIRONMENTS = new FakeEnvironments();

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.dev_admin_api_url", () -> ENVIRONMENTS.url("dev"));
        registry.add("spring.flyway.placeholders.test_admin_api_url", () -> ENVIRONMENTS.url("test"));
        registry.add("spring.flyway.placeholders.prod_admin_api_url", () -> ENVIRONMENTS.url("prod"));
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcClient jdbc;

    protected JsonHttp http;

    @BeforeEach
    void cleanDatabase() {
        http = new JsonHttp("http://localhost:" + port);
        ENVIRONMENTS.reset();
        // history tables are append-only even for their owner; a test database starts over by truncating
        jdbc.sql("""
                TRUNCATE promotion_request, route_deployment, response_profile_deployment, route_definition_history,
                         route_definition, project_environment, project, response_profile_history, response_profile
                         RESTART IDENTITY CASCADE
                """).update();
    }

    protected JsonHttp.Response as(String user, String method, String path, String json) {
        String token = Base64.getEncoder().encodeToString((user + ":" + user).getBytes(StandardCharsets.UTF_8));
        return http.send(method, "/api/v1" + path, json, Map.of("Authorization", "Basic " + token));
    }

    protected JsonHttp.Response get(String user, String path) {
        return as(user, "GET", path, null);
    }

    protected JsonHttp.Response post(String user, String path, String json) {
        return as(user, "POST", path, json);
    }

    protected JsonHttp.Response put(String user, String path, String json) {
        return as(user, "PUT", path, json);
    }

    /** Registers a project and configures it in every environment; returns its ID. */
    protected long project(String maintask, String prefix, String externalId) {
        JsonHttp.Response created = post("editor", "/projects", """
                {"externalProjectId": %s, "maintask": "%s", "permissionPrefix": "%s", "ownerTeam": "platform"}
                """.formatted(externalId == null ? "null" : "\"" + externalId + "\"", maintask, prefix));
        assertStatus(created, 201);
        long id = created.json().get("id").longValue();
        for (String environment : java.util.List.of("DEV", "TEST", "PROD")) {
            assertStatus(put("lead", "/projects/" + id + "/environments/" + environment, """
                    {"baseUrl": "http://%s.%s.svc:8080", "enabled": true}
                    """.formatted(maintask, environment.toLowerCase(java.util.Locale.ROOT))), 200);
        }
        return id;
    }

    protected long profile(String name) {
        JsonHttp.Response created = post("editor", "/response-profiles", """
                {"name": "%s", "description": "LogProjectResponse karşılığı", "definition": %s, "comment": "ilk"}
                """.formatted(name, com.acme.broker.core.testing.CoreFixtures.LOG_PROJECT_PROFILE));
        assertStatus(created, 201);
        return created.json().get("id").longValue();
    }

    protected long route(long projectId, String body) {
        JsonHttp.Response created = post("editor", "/projects/" + projectId + "/routes", body);
        assertStatus(created, 201);
        return created.json().get("id").longValue();
    }

    protected static String addOrderNote(String subtask, Long profileId) {
        return """
                {"subtask": "%s", "httpMethod": "POST", "pathTemplate": "/orders/{id}/notes",
                 "permissions": ["ORDER_WRITE"], "foreignPermissions": ["CRM_AGENT"], "anonymous": false,
                 "responseProfileId": %s, "definition": %s, "comment": "Siparişe not ekleme"}
                """.formatted(subtask, profileId == null ? "null" : profileId.toString(),
                com.acme.broker.core.testing.CoreFixtures.ADD_ORDER_NOTE);
    }

    protected static void assertStatus(JsonHttp.Response response, int status) {
        org.assertj.core.api.Assertions.assertThat(response.status()).as(response.text()).isEqualTo(status);
    }
}
