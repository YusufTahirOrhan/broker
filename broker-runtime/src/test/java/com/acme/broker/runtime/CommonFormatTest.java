package com.acme.broker.runtime;

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

import com.acme.broker.core.session.SessionRecord;
import com.acme.broker.core.session.SessionRecordCodec;
import com.acme.broker.core.testing.FakeHttpServer;
import com.acme.broker.core.testing.JsonHttp;
import com.acme.broker.runtime.route.RouteCache;
import com.acme.broker.runtime.session.ApplicationList;
import com.acme.broker.runtime.support.RuntimeFixture;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.JsonNode;

/**
 * The application list from Redis, with readiness, and the common error format instead of the legacy bodies
 * ({@code broker.compat.legacy-responses: false}). Logs go to the console; Kafka is not needed.
 */
@Tag("docker")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "broker.log-sink=console",
        "broker.kafka.warmup=false",
        "broker.compat.legacy-responses=false",
        "broker.applications.mode=redis",
        "broker.applications.key=test:applications",
        "broker.applications.refresh-interval=1h"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CommonFormatTest {

    static final FakeHttpServer backend = FakeHttpServer.start();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        RuntimeFixture.register(registry);
        Path spool = Files.createTempDirectory("spool");
        registry.add("broker.spool.directory", spool::toString);
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

    private JsonHttp.Response broker(String token, String body) {
        return new JsonHttp("http://localhost:" + port).send("POST", "/broker", body,
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

        assertError(broker("tok", known.formatted("kiosk")), 403, "DENIED");
        assertError(broker("other", known.formatted("web")), 401, "DENIED");
        assertError(broker("other", known.formatted("web").replace("get_order", "nope")), 401, "DENIED");
        assertError(broker("tok", known.formatted("web").replace("get_order", "nope")), 404, "UNKNOWN_TASK");
        assertThat(backend.requests()).isEmpty();

        backend.json("GET", "/orders/1", 200, "{\"id\": 1}");
        assertThat(broker("tok", known.formatted("web")).status()).isEqualTo(200);
    }

    private static void assertError(JsonHttp.Response response, int status, String outcome) {
        assertThat(response.status()).as(response.text()).isEqualTo(status);
        JsonNode body = response.json();
        assertThat(body.get("result").booleanValue()).isFalse();
        assertThat(body.get("status").intValue()).isEqualTo(status);
        assertThat(body.get("outcome").stringValue()).isEqualTo(outcome);
    }

    private double failedRefreshes() {
        var counter = meters.find("broker.applications.refresh.failed").counter();
        return counter == null ? 0 : counter.count();
    }
}
