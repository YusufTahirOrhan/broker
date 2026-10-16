package com.acme.broker.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.acme.broker.core.json.Json;
import com.acme.broker.core.testing.FakeHttpServer;
import com.acme.broker.core.testing.JsonHttp;
import com.acme.broker.envadmin.testing.RuntimeContainers;
import com.acme.broker.login.LoginApplication;
import com.acme.broker.runtime.route.RouteCache;
import com.acme.broker.runtime.support.RuntimeFixture;

import tools.jackson.databind.JsonNode;

/**
 * The broker and the real login service together, as frontends use them: login through the broker, a request with
 * the token, the session bound to the client, logout. The login service runs in this JVM as a second application
 * with static users (its {@code local} mode) and a fake Koordinatör; logs go to the console.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "broker.log-sink=console",
        "broker.kafka.warmup=false",
        "broker.applications.mode=static",
        "broker.applications.list=web",
        "broker.forward.user-context-secret=e2e-k3y"
})
class LoginEndToEndTest {

    static final FakeHttpServer koordinator = FakeHttpServer.start();
    static final FakeHttpServer orders = FakeHttpServer.start();
    static final ConfigurableApplicationContext login = startLoginService();

    static final String IP = "10.20.30.40";
    static final String AGENT = "Mozilla/5.0 (X11; Linux x86_64)";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        RuntimeFixture.register(registry);
        Path spool = Files.createTempDirectory("spool");
        registry.add("broker.spool.directory", spool::toString);
    }

    /** The login service with its own settings only; the broker's application.yml must not reach it. */
    private static ConfigurableApplicationContext startLoginService() {
        return new SpringApplicationBuilder(LoginApplication.class)
                .profiles("local")
                .properties(Map.ofEntries(
                        Map.entry("spring.config.location", "optional:classpath:/login-e2e/"),
                        Map.entry("spring.application.name", "broker-login"),
                        Map.entry("server.port", "0"),
                        Map.entry("spring.data.redis.host", RuntimeContainers.redisHost()),
                        Map.entry("spring.data.redis.port", Integer.toString(RuntimeContainers.redisPort())),
                        Map.entry("login.ldap.mode", "static"),
                        Map.entry("login.ldap.users.ali", "parola"),
                        Map.entry("login.user-context-secret", "e2e-k3y"),
                        Map.entry("login.applications.refresh-interval", "1h"),
                        Map.entry("koordinator.url", koordinator.baseUrl()),
                        // the broker's classpath brings JDBC and Flyway along; the login service uses neither
                        Map.entry("spring.datasource.url", RuntimeContainers.ORACLE.url()),
                        Map.entry("spring.datasource.username", RuntimeContainers.READER),
                        Map.entry("spring.datasource.password", com.acme.broker.core.testing.OracleDatabase.PASSWORD),
                        Map.entry("spring.flyway.enabled", "false")))
                .run();
    }

    private static int loginPort() {
        return ((WebServerApplicationContext) login).getWebServer().getPort();
    }

    @AfterAll
    static void stop() {
        login.close();
        koordinator.close();
        orders.close();
    }

    @LocalServerPort
    int port;

    @Autowired
    RouteCache cache;

    private JsonHttp.Response broker(String token, String body, Map<String, String> headers) {
        Map<String, String> all = new HashMap<>(Map.of("X-Token", token, "X-Clientip", IP, "X-User-Agent", AGENT));
        all.putAll(headers);
        return new JsonHttp("http://localhost:" + port).send("POST", "/broker", body, all);
    }

    @Test
    void loginRequestOtherClientLogout() {
        RuntimeFixture.reset();
        RuntimeFixture.project("giris", "http://localhost:" + loginPort());
        RuntimeFixture.route("giris", "login", "POST", "/login", List.of(), true, null, BrokerFlowTest.LOGIN);
        RuntimeFixture.route("giris", "logout", "POST", "/logout", List.of(), true, null, BrokerFlowTest.LOGOUT);
        RuntimeFixture.project("order-service", orders.baseUrl());
        RuntimeFixture.route("order-service", "get_order", "GET", "/orders/{id}", List.of("ORDER_READ"), false, null, """
                {"request": {"fields": [{"name": "orderId", "type": "integer", "required": true}],
                             "pathVariables": {"id": "${request.orderId}"}}}
                """);
        cache.reload();
        koordinator.json("GET", "/users/ali/permissions", 200, "{\"yetkiler\": [\"ORDER_READ\"]}");
        orders.json("GET", "/orders/7", 200, "{\"id\": 7}");
        String order = "{\"maintask\": \"order-service\", \"subtask\": \"get_order\", \"orderId\": 7, \"uid\": \"ali\", "
                + "\"application\": \"web\"}";
        String sessionInvalid = "{\"response\": false, \"status\": 301, \"reason\": \"check X-Token\"}";

        // 1. login through the broker
        JsonHttp.Response loggedIn = broker("login", """
                {"maintask": "giris", "subtask": "login", "uid": "ali", "password": "parola", "application": "web"}
                """, Map.of());
        assertThat(loggedIn.status()).isEqualTo(200);
        JsonNode body = loggedIn.json();
        assertThat(Json.fieldNames(body)).containsExactly("response", "status", "LDAP", "login", "X-Token");
        String token = body.get("X-Token").stringValue();

        // 2. the token works from the same client
        JsonHttp.Response ok = broker(token, order, Map.of());
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.json()).isEqualTo(Json.parse("{\"id\": 7}"));
        assertThat(orders.requests("/orders/7").getFirst().header("X-uid")).isEqualTo("ali");

        // 3. another browser with the same token is not the session's client
        JsonHttp.Response stolen = broker(token, order, Map.of("X-User-Agent", "curl/8.0"));
        assertThat(stolen.status()).isEqualTo(200);
        assertThat(stolen.json()).isEqualTo(Json.parse(sessionInvalid));

        // 4. nor can it log the user out
        JsonHttp.Response foreignLogout = broker("logout", "{\"uid\": \"ali\", \"application\": \"web\"}",
                Map.of("X-User-Agent", "curl/8.0"));
        assertThat(foreignLogout.json()).isEqualTo(Json.parse("""
                {"response": false, "status": 301, "message": "something went wrong with logging out."}
                """));
        assertThat(broker(token, order, Map.of()).status()).isEqualTo(200);
        assertThat(orders.requests("/orders/7")).hasSize(2);

        // 5. the user's own logout closes the session
        JsonHttp.Response logout = broker("logout", "{\"uid\": \"ali\", \"application\": \"web\"}", Map.of());
        assertThat(logout.json()).isEqualTo(Json.parse("{\"response\": true, \"status\": 200, \"message\": \"logged out\"}"));

        // 6. the same request no longer passes
        assertThat(broker(token, order, Map.of()).json()).isEqualTo(Json.parse(sessionInvalid));
        assertThat(orders.requests("/orders/7")).hasSize(2);
    }
}
