package com.acme.yapi.sink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.logging.EventCodec;
import com.acme.yapi.core.logging.LogEvent;
import com.acme.yapi.sink.clickhouse.ClickHouseSink;
import com.acme.yapi.sink.elastic.ElasticSink;
import com.acme.yapi.sink.fake.Events;
import com.acme.yapi.sink.fake.FakeClickHouse;
import com.acme.yapi.sink.fake.FakeElastic;
import com.acme.yapi.sink.kafka.DeadLetters;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(properties = {
        "yapi.sink.retry-interval=200ms",
        "spring.kafka.consumer.fetch-max-wait=100ms",
        "spring.kafka.consumer.fetch-min-size=1",
        "spring.kafka.listener.concurrency=1"
})
@EmbeddedKafka(partitions = 1, topics = {"yapi.logs.v1", "yapi.logs.v1.dlq"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class SinkFlowTest {

    static final FakeElastic elastic = new FakeElastic();
    static final FakeClickHouse clickhouse = new FakeClickHouse();
    static final TopicPartition PARTITION = new TopicPartition("yapi.logs.v1", 0);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("yapi.sink.elastic.url", elastic::url);
        registry.add("yapi.sink.clickhouse.url", clickhouse::url);
    }

    @AfterAll
    static void stop() {
        elastic.close();
        clickhouse.close();
    }

    @Autowired
    KafkaTemplate<String, byte[]> kafka;

    @Autowired
    EmbeddedKafkaBroker embeddedKafka;

    @BeforeEach
    void up() {
        elastic.down(false);
        clickhouse.down(false);
    }

    private void send(LogEvent event) throws Exception {
        kafka.send("yapi.logs.v1", event.requestId(), EventCodec.toBytes(event)).get();
    }

    @Test
    void bothTargetsGetTheEventsWhateverTheirOrder() throws Exception {
        String id = Events.newRequestId();
        send(Events.response(id));
        send(Events.request(id, "order-service"));

        await().atMost(Duration.ofSeconds(20)).until(() -> {
            ObjectNode main = elastic.document("requests_2026-10", id);
            return main != null && main.has("data_response") && main.has("adres");
        });
        ObjectNode main = elastic.document("requests_2026-10", id);
        assertThat(main.get("request_id").stringValue()).isEqualTo(id);
        assertThat(main.get("token").stringValue()).isEqualTo("3f2b8c1e-9a4d-4e7b-b6c2-5d1e8f0a9c47");
        assertThat(main.get("password").stringValue()).isEqualTo("xxxx");
        assertThat(main.get("request_date").stringValue()).isEqualTo("2026-10-08T11:28:41");
        assertThat(main.get("perm_list").size()).isEqualTo(2);
        assertThat(main.get("data_response")).isEqualTo(Json.parse("{\"BACKEND_STATUS\": 201, \"status\": 200, \"data\": \"\"}"));
        JsonNode yapiFields = main.get("yapi");
        assertThat(yapiFields.get("received_at").stringValue()).isEqualTo("2026-10-08T11:28:41.425Z");
        assertThat(yapiFields.get("responded_at").stringValue()).isEqualTo("2026-10-08T11:28:41.512Z");
        assertThat(yapiFields.get("outcome").stringValue()).isEqualTo("SUCCESS");
        assertThat(yapiFields.get("route_version").intValue()).isEqualTo(3);
        long parameterDocuments = elastic.documents().values().stream()
                .filter(document -> document.has("adres") && document.get("adres").has("parameter_name")
                        && "b1f4c2d8-6e3a-4f5b-9c7d-2a8e0f1b3c4d".equals(Json.stringField(document, "query_id"))
                        && !id.equals(Json.stringField(document, "request_id")))
                .count();
        assertThat(parameterDocuments).isGreaterThanOrEqualTo(2);

        await().atMost(Duration.ofSeconds(20)).until(() -> clickhouse.rows(id).size() == 2);
        JsonNode request = clickhouse.rows(id).stream()
                .filter(row -> "REQUEST".equals(Json.stringField(row, "event_type"))).findFirst().orElseThrow();
        assertThat(Json.fieldNames(request)).doesNotContain("token", "perm_list", "client_response", "query_id");
        assertThat(request.get("status_code").intValue()).isZero();
        assertThat(request.get("outcome").stringValue()).isEmpty();
        assertThat(Json.parse(request.get("body").stringValue()).get("adres").get("il").stringValue()).isEqualTo("Ankara");
        assertThat(clickhouse.queries()).allSatisfy(query -> assertThat(query)
                .contains("INSERT INTO yapi_logs.yapi_log FORMAT JSONEachRow")
                .contains("date_time_input_format=best_effort"));

        awaitCommitted(ElasticSink.GROUP);
        awaitCommitted(ClickHouseSink.GROUP);
    }

    @Test
    void aTargetThatIsDownHoldsBackOnlyItsOwnGroup() throws Exception {
        elastic.down(true);
        int calls = elastic.bulkCalls.get();
        String id = Events.newRequestId();
        send(Events.request(id, "order-service"));

        await().atMost(Duration.ofSeconds(20)).until(() -> clickhouse.rows(id).size() == 1);
        awaitCommitted(ClickHouseSink.GROUP);
        await().atMost(Duration.ofSeconds(20)).until(() -> elastic.bulkCalls.get() >= calls + 3);
        assertThat(committed(ElasticSink.GROUP)).isLessThan(endOffset());

        elastic.down(false);
        await().atMost(Duration.ofSeconds(20)).until(() -> elastic.document("requests_2026-10", id) != null);
        awaitCommitted(ElasticSink.GROUP);
    }

    @Test
    void poisonMessagesGoToTheDlqWithoutStoppingThePartition() throws Exception {
        String key = "poison-" + UUID.randomUUID();
        kafka.send("yapi.logs.v1", key, "not json".getBytes(StandardCharsets.UTF_8)).get();
        String id = Events.newRequestId();
        send(Events.request(id, "order-service"));

        await().atMost(Duration.ofSeconds(20)).until(() -> clickhouse.rows(id).size() == 1
                && elastic.document("requests_2026-10", id) != null);
        List<ConsumerRecord<String, byte[]>> dead = awaitDeadLetters(key, 2);
        assertThat(dead).extracting(record -> header(record, DeadLetters.SINK_HEADER))
                .containsExactlyInAnyOrder("elastic", "clickhouse");
        assertThat(header(dead.getFirst(), DeadLetters.REASON_HEADER)).startsWith("Ayrıştırılamadı");
        assertThat(header(dead.getFirst(), DeadLetters.ORIGIN_HEADER)).startsWith("yapi.logs.v1/0@");
        assertThat(new String(dead.getFirst().value(), StandardCharsets.UTF_8)).isEqualTo("not json");
    }

    @Test
    void eventsATargetRefusesGoToTheDlq() throws Exception {
        String bad = Events.newRequestId();
        String good = Events.newRequestId();
        elastic.reject(bad);
        clickhouse.poison("refused-by-clickhouse");
        send(Events.request(bad, "refused-by-clickhouse"));
        send(Events.request(good, "order-service"));

        await().atMost(Duration.ofSeconds(20)).until(() -> clickhouse.rows(good).size() == 1
                && elastic.document("requests_2026-10", good) != null);
        List<ConsumerRecord<String, byte[]>> dead = awaitDeadLetters(bad, 2);
        assertThat(dead).extracting(record -> header(record, DeadLetters.REASON_HEADER))
                .anySatisfy(reason -> assertThat(reason).startsWith("Elastic 400"))
                .anySatisfy(reason -> assertThat(reason).startsWith("ClickHouse satırı kabul etmedi"));
        assertThat(elastic.document("requests_2026-10", bad)).isNull();
        assertThat(clickhouse.rows(bad)).isEmpty();
        awaitCommitted(ElasticSink.GROUP);
        awaitCommitted(ClickHouseSink.GROUP);
    }

    private List<ConsumerRecord<String, byte[]>> awaitDeadLetters(String key, int count) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-reader-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<String, byte[]>> found = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(properties, new StringDeserializer(),
                new ByteArrayDeserializer())) {
            consumer.subscribe(List.of("yapi.logs.v1.dlq"));
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (found.size() < count && System.nanoTime() < deadline) {
                for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(200))) {
                    if (key.equals(record.key())) {
                        found.add(record);
                    }
                }
            }
        }
        assertThat(found).hasSize(count);
        return found;
    }

    private static String header(ConsumerRecord<String, byte[]> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private void awaitCommitted(String group) {
        await().atMost(Duration.ofSeconds(20)).until(() -> committed(group) == endOffset());
    }

    private long committed(String group) throws Exception {
        try (Admin admin = admin()) {
            Map<TopicPartition, OffsetAndMetadata> offsets = admin.listConsumerGroupOffsets(group)
                    .partitionsToOffsetAndMetadata().get();
            OffsetAndMetadata offset = offsets.get(PARTITION);
            return offset == null ? -1 : offset.offset();
        }
    }

    private long endOffset() throws Exception {
        try (Admin admin = admin()) {
            return admin.listOffsets(Map.of(PARTITION, OffsetSpec.latest())).partitionResult(PARTITION).get().offset();
        }
    }

    private Admin admin() {
        return Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString()));
    }
}
