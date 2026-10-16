package com.acme.broker.login;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.acme.broker.core.context.UserContextToken;
import com.acme.broker.core.json.Json;
import com.acme.broker.core.session.SessionRecord;
import com.acme.broker.core.session.SessionRecordCodec;
import com.acme.broker.core.testing.FakeHttpServer;
import com.acme.broker.core.testing.JsonHttp;
import com.acme.broker.login.session.ApplicationSync;
import com.acme.broker.login.support.EmbeddedLdap;
import com.acme.broker.login.support.TestRedis;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.JsonNode;

/** The login service against a real directory (UnboundID), Redis and a fake Koordinatör; bodies key for key. */
@Tag("docker")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "koordinator.auth-value=Bearer koordinator-token",
        "login.koordinator-secret=k-secret",
        "login.user-context-secret=k3y",
        "login.applications.refresh-interval=1h"
})
@ExtendWith(OutputCaptureExtension.class)
class LoginApiTest {

    static final EmbeddedLdap LDAP = EmbeddedLdap.start().user("ali", "s3cret-Parola").user("ayse", "diger");
    static final FakeHttpServer koordinator = FakeHttpServer.start();
    static final String IP = "10.20.30.40";
    static final String AGENT = "Mozilla/5.0 (Windows NT 10.0)";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestRedis.register(registry, 0);
        registry.add("login.ldap.urls", LDAP::url);
        registry.add("login.ldap.principal-pattern", EmbeddedLdap::principalPattern);
        registry.add("koordinator.url", koordinator::baseUrl);
    }

    @AfterAll
    static void stop() {
        koordinator.close();
        LDAP.stop();
    }

    @LocalServerPort
    int port;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    ApplicationSync applications;

    @Autowired
    MeterRegistry meters;

    @BeforeEach
    void reset() {
        koordinator.reset();
        redis.delete(List.of("user:session:ali", "user:session:ayse", "broker:applications"));
        koordinator.json("GET", "/users/ali/permissions", 200,
                "{\"yetkiler\": [\"LOGPROJECT_TEMEL\", \"SORGU_TEMEL\"], \"ad\": \"Ali\"}");
    }

    /** A call as the broker makes it: signed, with the frontend's client headers unless {@code headers} say else. */
    private JsonHttp.Response call(String path, String body, Map<String, String> headers) {
        Map<String, String> all = new HashMap<>();
        all.put("X-Clientip", IP);
        all.put("X-User-Agent", AGENT);
        all.put("X-User-Context", UserContextToken.sign("k3y".getBytes(), null, UUID.randomUUID().toString(),
                Instant.now()));
        all.putAll(headers);
        all.values().removeIf(java.util.Objects::isNull);
        return new JsonHttp("http://localhost:" + port).send("POST", path, body, all);
    }

    private JsonHttp.Response login(String uid, String password) {
        return call("/login", Json.write(Map.of("uid", uid, "password", password)), Map.of());
    }

    private SessionRecord stored(String uid) {
        return SessionRecordCodec.DEFAULT.read(redis.opsForValue().get("user:session:" + uid)).orElse(null);
    }

    @Test
    void aSuccessfulLoginWritesTheLegacyRecordForOneDay(CapturedOutput output) {
        JsonHttp.Response response = login("ali", "s3cret-Parola");

        assertThat(response.status()).isEqualTo(200);
        JsonNode body = response.json();
        assertThat(Json.fieldNames(body)).containsExactly("response", "status", "LDAP", "login", "X-Token");
        assertThat(body.get("response").isBoolean() && body.get("response").booleanValue()).isTrue();
        assertThat(body.get("status").isInt()).isTrue();
        assertThat(body.get("status").intValue()).isEqualTo(200);
        assertThat(body.get("LDAP").booleanValue()).isTrue();
        assertThat(body.get("login").booleanValue()).isTrue();
        String token = body.get("X-Token").stringValue();
        assertThat(UUID.fromString(token).version()).isEqualTo(4);

        assertThat(stored("ali")).isEqualTo(new SessionRecord("ali", token, IP, AGENT,
                List.of("LOGPROJECT_TEMEL", "SORGU_TEMEL")));
        assertThat(redis.getExpire("user:session:ali")).isBetween(86_000L, 86_400L);
        assertThat(koordinator.requests("/users/ali/permissions").getFirst().header("Authorization"))
                .isEqualTo("Bearer koordinator-token");
        assertThat(output.getAll()).doesNotContain("s3cret-Parola");
    }

    @Test
    void aNewLoginReplacesTheOldSession() {
        String first = login("ali", "s3cret-Parola").json().get("X-Token").stringValue();
        String second = login("ali", "s3cret-Parola").json().get("X-Token").stringValue();

        assertThat(second).isNotEqualTo(first);
        assertThat(stored("ali").token()).isEqualTo(second);
    }

    @Test
    void aWrongPasswordIsRefusedWithoutAskingTheKoordinator(CapturedOutput output) {
        JsonHttp.Response response = login("ali", "yanlis-Parola");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.text()).isEqualTo("{\"response\":false,\"status\":401,\"Ldap\":false,\"login\":false}");
        assertThat(koordinator.requests()).isEmpty();
        assertThat(stored("ali")).isNull();
        assertThat(output.getAll()).doesNotContain("yanlis-Parola");
    }

    @Test
    void anEmptyPasswordOrUidNeverBinds() {
        // the directory allows anonymous binds; an empty password must not fall back to one
        for (String body : List.of("{\"uid\": \"ali\", \"password\": \"\"}", "{\"uid\": \"ali\"}",
                "{\"uid\": \"\", \"password\": \"x\"}", "{}", "not json", "")) {
            JsonHttp.Response response = call("/login", body, Map.of());
            assertThat(response.status()).as(body).isEqualTo(200);
            assertThat(response.json()).as(body).isEqualTo(Json.parse("""
                    {"response": false, "status": 401, "Ldap": false, "login": false}
                    """));
        }
        assertThat(koordinator.requests()).isEmpty();
    }

    @Test
    void withoutPermissionsTheLoginFailsAfterAGoodPassword() {
        koordinator.json("GET", "/users/ayse/permissions", 500, "{}");

        JsonHttp.Response response = login("ayse", "diger");

        assertThat(response.text()).isEqualTo("{\"response\":false,\"status\":401,\"Ldap\":true,\"login\":false}");
        assertThat(stored("ayse")).isNull();
    }

    @Test
    void logoutClosesOnlyTheSessionOfTheClientThatOpenedIt() {
        login("ali", "s3cret-Parola");
        String refused = "{\"response\":false,\"status\":301,\"message\":\"something went wrong with logging out.\"}";

        assertThat(call("/logout", "{\"uid\": \"ali\"}", Map.of("X-User-Agent", "other")).text()).isEqualTo(refused);
        assertThat(call("/logout", "{\"uid\": \"ali\"}", Map.of("X-Clientip", "10.0.0.9")).text()).isEqualTo(refused);
        assertThat(call("/logout", "{\"uid\": \"ali\"}", java.util.Collections.singletonMap("X-Clientip", null))
                .text()).isEqualTo(refused);
        assertThat(stored("ali")).isNotNull();

        JsonHttp.Response loggedOut = call("/logout", "{\"uid\": \"ali\"}", Map.of());
        assertThat(loggedOut.status()).isEqualTo(200);
        assertThat(loggedOut.text()).isEqualTo("{\"response\":true,\"status\":200,\"message\":\"logged out\"}");
        assertThat(stored("ali")).isNull();

        assertThat(call("/logout", "{\"uid\": \"ali\"}", Map.of()).text()).isEqualTo(refused);
        assertThat(call("/logout", "{}", Map.of()).text()).isEqualTo(refused);
    }

    @Test
    void theKoordinatorClosesASessionWithItsSecret() {
        login("ali", "s3cret-Parola");
        JsonHttp http = new JsonHttp("http://localhost:" + port);

        assertThat(http.send("POST", "/internal/v1/sessions/ali/logout", null, Map.of()).status()).isEqualTo(403);
        assertThat(http.send("POST", "/internal/v1/sessions/ali/logout", null,
                Map.of("X-Koordinator-Secret", "wrong")).status()).isEqualTo(403);
        assertThat(stored("ali")).isNotNull();
        assertThat(http.send("POST", "/internal/v1/sessions/ali/logout", null,
                Map.of("X-Koordinator-Secret", "k-secret")).status()).isEqualTo(204);
        assertThat(stored("ali")).isNull();
        assertThat(http.send("POST", "/internal/v1/sessions/ali/logout", null,
                Map.of("X-Koordinator-Secret", "k-secret")).status()).isEqualTo(404);
    }

    @Test
    void onlyCallsTheBrokerSignedRecentlyAreServed() {
        String body = "{\"uid\": \"ali\", \"password\": \"s3cret-Parola\"}";
        Map<String, String> unsigned = java.util.Collections.singletonMap("X-User-Context", null);
        String old = UserContextToken.sign("k3y".getBytes(), null, "r1", Instant.now().minusSeconds(120));
        String foreign = UserContextToken.sign("other-key".getBytes(), null, "r1", Instant.now());

        for (Map<String, String> headers : List.of(unsigned, Map.of("X-User-Context", old),
                Map.of("X-User-Context", foreign), Map.of("X-User-Context", "v1.e30.AAAA"))) {
            JsonHttp.Response refused = call("/login", body, headers);
            assertThat(refused.status()).isEqualTo(403);
            assertThat(call("/logout", "{\"uid\": \"ali\"}", headers).status()).isEqualTo(403);
        }
        // other spellings Spring MVC maps to the same handlers, and paths no handler has yet
        for (String path : List.of("/login;x=1", "/%6Cogin", "/logout;x=1", "/;x=1/login", "/internal/../login",
                "/actuator/../login", "/nope")) {
            assertThat(call(path, body, unsigned).status()).as(path).isEqualTo(403);
        }
        assertThat(koordinator.requests()).isEmpty();
        assertThat(stored("ali")).isNull();

        assertThat(call("/login", body, Map.of()).status()).isEqualTo(200);
        assertThat(call("/nope", body, Map.of()).status()).isEqualTo(404);
        assertThat(new JsonHttp("http://localhost:" + port).get("/actuator/health").status()).isEqualTo(200);
    }

    @Test
    void theApplicationListFollowsTheKoordinatorsProjects() {
        double failures = meters.counter("login.applications.sync.failed").count();
        koordinator.json("GET", "/projects", 200, """
                [{"id": 1, "code": "web", "name": "Web"}, {"id": 2, "code": "Mobil", "name": "Mobil"}, {"id": 3}]
                """);

        applications.sync();
        assertThat(Json.parse(redis.opsForValue().get("broker:applications")))
                .isEqualTo(Json.parse("[\"web\", \"Mobil\"]"));
        assertThat(redis.getExpire("broker:applications")).isEqualTo(-1L);

        koordinator.json("GET", "/projects", 500, "{}");
        applications.sync();
        koordinator.json("GET", "/projects", 200, "[]");
        applications.sync();

        assertThat(Json.parse(redis.opsForValue().get("broker:applications")))
                .isEqualTo(Json.parse("[\"web\", \"Mobil\"]"));
        assertThat(meters.counter("login.applications.sync.failed").count()).isEqualTo(failures + 2);
    }
}
