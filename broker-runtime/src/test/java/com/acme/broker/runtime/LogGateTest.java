package com.acme.broker.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.acme.broker.core.json.Json;
import com.acme.broker.core.logging.EventCodec;
import com.acme.broker.core.logging.LogEvent;
import com.acme.broker.core.testing.CoreFixtures;
import com.acme.broker.core.testing.FakeHttpServer;
import com.acme.broker.envadmin.testing.JsonHttp;
import com.acme.broker.runtime.log.Spool;
import com.acme.broker.runtime.route.RouteCache;
import com.acme.broker.runtime.support.EventCollector;
import com.acme.broker.runtime.support.RuntimeFixture;

/**
 * The log gate with a Kafka that does not acknowledge: the topic does not exist and cannot be auto-created, so every
 * send fails within {@code max.block.ms}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "broker.kafka.topic=broker.logs.gate",
        "broker.kafka.warmup=false",
        "broker.kafka.ack-timeout=1s",
        "broker.spool.resend-interval=1h"
})
@EmbeddedKafka(partitions = 1, bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        brokerProperties = "auto.create.topics.enable=false")
class LogGateTest {

    static final FakeHttpServer backend = FakeHttpServer.start();
    static final Path spoolDirectory = directory();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        RuntimeFixture.register(registry);
        registry.add("broker.spool.directory", spoolDirectory::toString);
    }

    @AfterAll
    static void stop() {
        backend.close();
    }

    @LocalServerPort
    int port;

    @Autowired
    RouteCache cache;

    @Autowired
    Spool spool;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    EmbeddedKafkaBroker kafka;

    @Test
    void withoutAcknowledgementTheServiceIsNotCalledAndTheRecordIsCompletedLater() throws Exception {
        RuntimeFixture.reset();
        RuntimeFixture.project("order-service", backend.baseUrl());
        RuntimeFixture.route("order-service", "add_order_note", "POST", "/orders/{id}/notes", List.of("ORDER_WRITE"),
                false, null, CoreFixtures.ADD_ORDER_NOTE);
        cache.reload();
        redis.opsForValue().set("broker:session:gate-token", "{\"userId\": \"u1\", \"permissions\": [\"ORDER_WRITE\"]}");
        backend.json("POST", "/orders/1/notes", 201, "{}");

        JsonHttp.Response response = new JsonHttp("http://localhost:" + port).send("POST", "/broker", """
                {"maintask": "order-service", "subtask": "add_order_note", "siparisNo": 1, "notMetni": "x",
                 "password": "gizli"}
                """, Map.of("X-Token", "gate-token"));

        assertThat(response.status()).isEqualTo(500);
        assertThat(response.json()).isEqualTo(Json.parse("{\"result\": false}"));
        String requestId = response.header("X-Request-Id");
        assertThat(requestId).isNotNull();
        assertThat(backend.requests()).isEmpty();

        List<Path> files = spooled();
        assertThat(files).hasSize(1);
        List<LogEvent> stored = Files.readAllLines(files.getFirst(), StandardCharsets.UTF_8).stream()
                .map(line -> EventCodec.fromJson(Json.parse(line))).toList();
        assertThat(stored).extracting(LogEvent::eventType)
                .containsExactly(LogEvent.EventType.REQUEST, LogEvent.EventType.RESPONSE);
        assertThat(stored).extracting(LogEvent::requestId).containsOnly(requestId);
        assertThat(Json.text(stored.getFirst().body().get("password"))).isEqualTo("xxxx");
        LogEvent responseEvent = stored.get(1);
        assertThat(responseEvent.outcome()).isEqualTo("LOG_UNAVAILABLE");
        assertThat(responseEvent.statusCode()).isEqualTo(500);
        assertThat(responseEvent.clientResponse()).isEqualTo(Json.parse("{\"result\": false}"));

        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBrokersAsString()))) {
            admin.createTopics(List.of(new NewTopic("broker.logs.gate", 1, (short) 1))).all().get();
        }
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).until(() -> {
            spool.resend();
            return spooled().isEmpty();
        });
        try (EventCollector collector = new EventCollector(kafka.getBrokersAsString(), "broker.logs.gate")) {
            assertThat(collector.await(requestId, 2)).extracting(LogEvent::outcome).containsExactly(null, "LOG_UNAVAILABLE");
        }
    }

    private static List<Path> spooled() throws java.io.IOException {
        try (Stream<Path> files = Files.list(spoolDirectory)) {
            return files.filter(file -> file.toString().endsWith(".jsonl")).sorted().toList();
        }
    }

    private static Path directory() {
        try {
            return Files.createTempDirectory("broker-gate-spool");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
