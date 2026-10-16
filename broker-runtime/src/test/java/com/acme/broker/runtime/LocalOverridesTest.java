package com.acme.broker.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.acme.broker.core.session.SessionRecord;
import com.acme.broker.core.session.SessionRecordCodec;
import com.acme.broker.core.testing.CoreFixtures;
import com.acme.broker.core.testing.FakeHttpServer;
import com.acme.broker.core.testing.JsonHttp;
import com.acme.broker.runtime.route.RouteCache;
import com.acme.broker.runtime.support.RuntimeFixture;

/** The {@code local} profile: DEV's routes plus {@code broker-local.json}, console log sink, no Kafka. */
@Tag("docker")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class LocalOverridesTest {

    static final FakeHttpServer dev = FakeHttpServer.start();
    static final FakeHttpServer laptop = FakeHttpServer.start();
    static final Path directory = directory();
    static final Path overrides = directory.resolve("broker-local.json");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        RuntimeFixture.register(registry);
        registry.add("broker.local-overrides", () -> overrides.toUri().toString());
        registry.add("broker.spool.directory", () -> directory.resolve("spool").toString());
    }

    @AfterAll
    static void stop() {
        dev.close();
        laptop.close();
    }

    @LocalServerPort
    int port;

    @Autowired
    RouteCache cache;

    @Autowired
    StringRedisTemplate redis;

    @Test
    void overridesRedirectProjectsAndAddRoutesWithoutRestart() throws IOException {
        write("""
                {"baseUrls": {"ORDER-SERVICE": "%s"},
                 "routes": [{"maintask": "order-service", "subtask": "cancel_order", "httpMethod": "POST",
                             "pathTemplate": "/orders/{id}/cancel", "permissions": ["ORDER_CANCEL"],
                             "definition": {"request": {"fields": [{"name": "orderId", "type": "integer", "required": true}],
                                                        "pathVariables": {"id": "${request.orderId}"},
                                                        "body": {"mode": "NONE"}},
                                            "response": {"type": "JSON"}}}]}
                """.formatted(laptop.baseUrl()));
        RuntimeFixture.reset();
        RuntimeFixture.project("order-service", dev.baseUrl());
        RuntimeFixture.route("order-service", "add_order_note", "POST", "/orders/{id}/notes", List.of("ORDER_WRITE"),
                false, null, CoreFixtures.ADD_ORDER_NOTE);
        cache.reload();
        redis.opsForValue().set(SessionRecordCodec.DEFAULT.key("dev.ali"), SessionRecordCodec.DEFAULT.write(
                new SessionRecord("dev.ali", "local-token", "127.0.0.1", "curl", List.of("ORDER_WRITE", "ORDER_CANCEL"))));
        laptop.json("POST", "/orders/3/notes", 201, "{\"where\": \"laptop\"}");
        laptop.json("POST", "/orders/3/cancel", 200, "{\"cancelled\": true}");
        dev.json("POST", "/orders/3/notes", 201, "{\"where\": \"dev\"}");

        assertThat(call("add_order_note", "\"siparisNo\": 3, \"notMetni\": \"x\"").json().get("where").stringValue())
                .isEqualTo("laptop");
        assertThat(call("cancel_order", "\"orderId\": 3").json().get("cancelled").booleanValue()).isTrue();

        write("{\"baseUrls\": {}, \"routes\": []}");
        await().atMost(Duration.ofSeconds(15)).until(() -> cache.find("order-service", "cancel_order").isEmpty());
        assertThat(call("add_order_note", "\"siparisNo\": 3, \"notMetni\": \"x\"").json().get("where").stringValue())
                .isEqualTo("dev");
    }

    private JsonHttp.Response call(String subtask, String fields) {
        JsonHttp.Response response = new JsonHttp("http://localhost:" + port).send("POST", "/broker",
                "{\"maintask\": \"order-service\", \"subtask\": \"" + subtask + "\", \"uid\": \"dev.ali\", "
                        + "\"application\": \"web\", " + fields + "}",
                Map.of("X-Token", "local-token", "X-Clientip", "127.0.0.1", "X-User-Agent", "curl"));
        assertThat(response.status()).as(response.text()).isLessThan(300);
        return response;
    }

    private static void write(String json) throws IOException {
        Files.writeString(overrides, json);
        // file systems with second resolution would hide a quick second write
        Files.setLastModifiedTime(overrides, FileTime.from(Instant.now().plusSeconds(Files.exists(overrides) ? 1 : 0)));
    }

    private static Path directory() {
        try {
            return Files.createTempDirectory("broker-local");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
