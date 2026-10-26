package com.acme.yapi.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.acme.yapi.core.session.SessionRecord;
import com.acme.yapi.core.session.SessionRecordCodec;
import com.acme.yapi.core.testing.FakeHttpServer;
import com.acme.yapi.core.testing.JsonHttp;
import com.acme.yapi.runtime.route.RouteCache;
import com.acme.yapi.runtime.session.ApplicationList;
import com.acme.yapi.runtime.support.RuntimeFixture;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.JsonNode;

/**
 * The application list from Redis, with readiness, and the common error format instead of the legacy bodies
 * ({@code yapi.compat.legacy-responses: false}). Logs go to the console; Kafka is not needed.
 */
@Tag("docker")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "yapi.log-sink=console",
        "yapi.kafka.warmup=false",
        "yapi.compat.legacy-responses=false",
        "yapi.applications.mode=redis",
        "yapi.applications.key=test:applications",
        "yapi.applications.refresh-interval=1h"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CommonFormatTest {

    static final FakeHttpServer backend = FakeHttpServer.start();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        RuntimeFixture.register(registry);
        Path spool = Files.createTempDirectory("spool");
        registry.add("yapi.spool.directory", spool::toString);
        // the list must not exist when the context starts
        RuntimeFixture.redis().delete("test:applications");
    }

    @AfterAll
    static void stop() {
        backend.close();
    }

    @LocalServerPort
    int port;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    ApplicationList applications;

    @Autowired
    RouteCache cache;

    @Autowired
    MeterRegistry meters;

    private JsonHttp.Response yapi(String token, String body) {
        return new JsonHttp("http://localhost:" + port).send("POST", "/yapi", body,
                Map.of("X-Token", token, "X-Clientip", "10.0.0.1", "X-User-Agent", "agent"));
    }

    private int readiness() {
        return new JsonHttp("http://localhost:" + port).get("/actuator/health/readiness").status();
    }

    @Test
    @Order(1)
    void theBrokerIsReadyOnlyOnceTheApplicationListWasRead() {
        assertThat(readiness()).isEqualTo(503);
        assertThat(applications.allows("web")).isFalse();

        redis.opsForValue().set("test:applications", "[\"web\", \"Mobil\"]");
        applications.refresh();

        assertThat(readiness()).isEqualTo(200);
        assertThat(applications.allows("WEB")).isTrue();
        assertThat(applications.allows("mobil")).isTrue();
        assertThat(applications.allows("kiosk")).isFalse();
        assertThat(applications.allows(null)).isFalse();
    }

    @Test
    @Order(2)
    void theLastListThatCouldBeReadStaysInUse() {
        double failures = failedRefreshes();
        for (String broken : List.of("[]", "{\"web\": true}", "not json")) {
            redis.opsForValue().set("test:applications", broken);
            applications.refresh();
        }
        redis.delete("test:applications");
        applications.refresh();

        assertThat(applications.allows("web")).isTrue();
        assertThat(readiness()).isEqualTo(200);
        assertThat(failedRefreshes()).isEqualTo(failures + 4);

        redis.opsForValue().set("test:applications", "[\"kiosk\"]");
        applications.refresh();
        assertThat(applications.allows("web")).isFalse();
        assertThat(applications.allows("kiosk")).isTrue();
        redis.opsForValue().set("test:applications", "[\"web\"]");
        applications.refresh();
    }

    @Test
    @Order(3)
    void withoutLegacyResponsesRefusalsUseTheCommonFormat() {
        RuntimeFixture.reset();
        RuntimeFixture.project("order-service", backend.baseUrl());
        RuntimeFixture.route("order-service", "get_order", "GET", "/orders/1", List.of("ORDER_READ"), false, null, "{}");
        cache.reload();
        redis.opsForValue().set(SessionRecordCodec.DEFAULT.key("u1"), SessionRecordCodec.DEFAULT.write(
                new SessionRecord("u1", "tok", "10.0.0.1", "agent", List.of("ORDER_READ"))));
        String known = "{\"maintask\": \"order-service\", \"subtask\": \"get_order\", \"uid\": \"u1\", \"application\": \"%s\"}";

        assertError(yapi("tok", known.formatted("kiosk")), 403, "DENIED");
        assertError(yapi("other", known.formatted("web")), 401, "DENIED");
        assertError(yapi("other", known.formatted("web").replace("get_order", "nope")), 401, "DENIED");
        assertError(yapi("tok", known.formatted("web").replace("get_order", "nope")), 404, "UNKNOWN_TASK");
        assertThat(backend.requests()).isEmpty();

        backend.json("GET", "/orders/1", 200, "{\"id\": 1}");
        assertThat(yapi("tok", known.formatted("web")).status()).isEqualTo(200);
    }

    @Test
    @Order(4)
    void theEndpointIsYapiNotTheLegacyPath() {
        backend.reset();
        backend.json("GET", "/orders/1", 200, "{\"id\": 1}");
        String body = "{\"maintask\": \"order-service\", \"subtask\": \"get_order\", \"uid\": \"u1\", \"application\": \"web\"}";
        Map<String, String> headers = Map.of("X-Token", "tok", "X-Clientip", "10.0.0.1", "X-User-Agent", "agent");

        assertThat(new JsonHttp("http://localhost:" + port).send("POST", "/broker", body, headers).status())
                .isEqualTo(404);
        assertThat(backend.requests()).isEmpty();
        assertThat(yapi("tok", body).status()).isEqualTo(200);
    }

    private static void assertError(JsonHttp.Response response, int status, String outcome) {
        assertThat(response.status()).as(response.text()).isEqualTo(status);
        JsonNode body = response.json();
        assertThat(body.get("result").booleanValue()).isFalse();
        assertThat(body.get("status").intValue()).isEqualTo(status);
        assertThat(body.get("outcome").stringValue()).isEqualTo(outcome);
    }

    private double failedRefreshes() {
        var counter = meters.find("yapi.applications.refresh.failed").counter();
        return counter == null ? 0 : counter.count();
    }
}
