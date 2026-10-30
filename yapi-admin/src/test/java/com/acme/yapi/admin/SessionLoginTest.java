package com.acme.yapi.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.acme.yapi.admin.support.AdminTestBase;
import com.acme.yapi.admin.support.CentralDatabase;
import com.acme.yapi.admin.support.FakeEnvironments;
import com.acme.yapi.core.testing.JsonHttp;

import tools.jackson.databind.JsonNode;

/**
 * Without the local profile the API signs users in with the login application's session, checked by the session
 * environment's Admin API (PROD); roles come from the session's Yapi permissions.
 */
@Tag("docker")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "yapi.admin.catalog.mode=memory",
        "yapi.admin.authorization.mode=memory",
        "yapi.admin.session.roles.VIEWER=YAPI_DEVELOPER,YAPI_ADMIN",
        "yapi.admin.session.roles.ROUTE_EDITOR=YAPI_DEVELOPER,YAPI_ADMIN",
        "yapi.admin.session.roles.APPROVER=YAPI_ADMIN,DEGISIKLIK_KURULU",
        "yapi.admin.login-url=https://giris.example.test/login"
})
class SessionLoginTest {

    static final FakeEnvironments ENVIRONMENTS = AdminTestBase.ENVIRONMENTS;
    static final String AGENT = "Mozilla/5.0 (Windows NT 10.0)";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        CentralDatabase.register(registry);
        registry.add("spring.flyway.placeholders.dev_admin_api_url", () -> ENVIRONMENTS.url("dev"));
        registry.add("spring.flyway.placeholders.preprod_admin_api_url", () -> ENVIRONMENTS.url("preprod"));
        registry.add("spring.flyway.placeholders.prod_admin_api_url", () -> ENVIRONMENTS.url("prod"));
    }

    @LocalServerPort
    int port;

    @BeforeEach
    void reset() {
        ENVIRONMENTS.reset();
    }

    /** The four headers of a session; every test uses its own tokens, since answers are kept across tests. */
    private static Map<String, String> session(String uid, String token) {
        return new HashMap<>(Map.of("X-Token", token, "X-Uid", uid, "X-Clientip", "10.0.0.5", "X-User-Agent", AGENT));
    }

    private JsonHttp.Response me(Map<String, String> headers) {
        return new JsonHttp("http://localhost:" + port).send("GET", "/api/v1/me", null, headers);
    }

    @Test
    void rolesComeFromTheSessionsYapiPermissions() {
        ENVIRONMENTS.get("PROD").session("12345", "tok-a-dev", "10.0.0.5", AGENT, "ORDER_READ", "YAPI_DEVELOPER");
        ENVIRONMENTS.get("PROD").session("23456", "tok-a-adm", "10.0.0.5", AGENT, "YAPI_ADMIN");
        ENVIRONMENTS.get("PROD").session("34567", "tok-a-krl", "10.0.0.5", AGENT, "DEGISIKLIK_KURULU");

        JsonHttp.Response developer = me(session("12345", "tok-a-dev"));
        assertThat(developer.status()).as(developer.text()).isEqualTo(200);
        assertThat(developer.json().get("username").stringValue()).isEqualTo("12345");
        assertThat(developer.json().get("displayName").stringValue()).isEqualTo("12345");
        assertThat(roles(developer)).containsExactly("VIEWER", "ROUTE_EDITOR");
        assertThat(roles(me(session("23456", "tok-a-adm")))).containsExactly("VIEWER", "ROUTE_EDITOR", "APPROVER");
        assertThat(roles(me(session("34567", "tok-a-krl")))).containsExactly("VIEWER", "APPROVER");

        assertThat(ENVIRONMENTS.get("PROD").sessionChecks.get()).isEqualTo(3);
        assertThat(ENVIRONMENTS.get("DEV").sessionChecks.get()).isZero();
    }

    @Test
    void withoutAValidSessionTheAnswerIs401AndWithoutAYapiPermission403() {
        ENVIRONMENTS.get("PROD").session("12345", "tok-b-dev", "10.0.0.5", AGENT, "YAPI_DEVELOPER");
        ENVIRONMENTS.get("PROD").session("45678", "tok-b-x", "10.0.0.5", AGENT, "ORDER_READ");

        assertUnauthenticated(me(Map.of()));
        for (String header : new String[]{"X-Token", "X-Uid", "X-Clientip", "X-User-Agent"}) {
            Map<String, String> missing = session("12345", "tok-b-dev");
            missing.remove(header);
            assertUnauthenticated(me(missing));
        }
        assertUnauthenticated(me(session("12345", "tok-b-eski")));
        Map<String, String> otherBrowser = session("12345", "tok-b-dev");
        otherBrowser.put("X-User-Agent", "curl/8.0");
        assertUnauthenticated(me(otherBrowser));

        JsonHttp.Response noRole = me(session("45678", "tok-b-x"));
        assertThat(noRole.status()).isEqualTo(403);
        assertThat(noRole.json().get("code").stringValue()).isEqualTo("FORBIDDEN");
        assertThat(noRole.json().get("detail").stringValue()).isEqualTo("Yapi Admin için rolünüz yok.");

        JsonHttp.Response editorOnly = new JsonHttp("http://localhost:" + port).send("POST",
                "/api/v1/promotions/1/approve", "{}", session("12345", "tok-b-dev"));
        assertThat(editorOnly.status()).isEqualTo(403);
    }

    @Test
    void answersAreKeptForAShortTimeAndFailuresAreNot() {
        ENVIRONMENTS.get("PROD").session("12345", "tok-c-dev", "10.0.0.5", AGENT, "YAPI_DEVELOPER");

        for (int i = 0; i < 3; i++) {
            assertThat(me(session("12345", "tok-c-dev")).status()).isEqualTo(200);
            assertUnauthenticated(me(session("12345", "tok-c-yanlis")));
        }
        assertThat(ENVIRONMENTS.get("PROD").sessionChecks.get()).isEqualTo(2);

        ENVIRONMENTS.get("PROD").silent = true;
        JsonHttp.Response unreachable = me(session("56789", "tok-c-yeni"));
        assertThat(unreachable.status()).isEqualTo(502);
        assertThat(unreachable.json().get("code").stringValue()).isEqualTo("ENV_UNREACHABLE");
        ENVIRONMENTS.get("PROD").silent = false;
        ENVIRONMENTS.get("PROD").session("56789", "tok-c-yeni", "10.0.0.5", AGENT, "YAPI_ADMIN");
        assertThat(me(session("56789", "tok-c-yeni")).status()).isEqualTo(200);
    }

    @Test
    void openApiAndSwaggerAreOffOutsideLocalAndDev() {
        ENVIRONMENTS.get("PROD").session("12345", "tok-d-dev", "10.0.0.5", AGENT, "YAPI_DEVELOPER");
        JsonHttp http = new JsonHttp("http://localhost:" + port);

        assertThat(http.send("GET", "/v3/api-docs", null, session("12345", "tok-d-dev")).status()).isEqualTo(404);
        assertThat(http.send("GET", "/swagger-ui.html", null, session("12345", "tok-d-dev")).status()).isEqualTo(404);
    }

    private static void assertUnauthenticated(JsonHttp.Response response) {
        assertThat(response.status()).as(response.text()).isEqualTo(401);
        assertThat(response.json().get("code").stringValue()).isEqualTo("UNAUTHENTICATED");
        assertThat(response.json().get("loginUrl").stringValue()).isEqualTo("https://giris.example.test/login");
    }

    private static java.util.List<String> roles(JsonHttp.Response response) {
        return response.json().get("roles").valueStream().map(JsonNode::stringValue).toList();
    }
}
