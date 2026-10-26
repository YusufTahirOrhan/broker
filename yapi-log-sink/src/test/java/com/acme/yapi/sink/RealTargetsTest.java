package com.acme.yapi.sink;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;

import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.logging.LogEvent;
import com.acme.yapi.sink.clickhouse.ClickHouseWriter;
import com.acme.yapi.sink.config.SinkProperties;
import com.acme.yapi.sink.elastic.ElasticBulkWriter;
import com.acme.yapi.sink.fake.Events;
import com.acme.yapi.sink.kafka.Rejection;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The writers against real servers: the design document's ClickHouse DDL, and Elasticsearch with the
 * {@code yapi-fields} component template composed into a {@code requests_*} template as in production.
 */
@Tag("docker")
class RealTargetsTest {

    static final GenericContainer<?> clickhouse = new GenericContainer<>("clickhouse/clickhouse-server:24.8-alpine")
            .withEnv("CLICKHOUSE_USER", "yapi")
            .withEnv("CLICKHOUSE_PASSWORD", "yapi")
            .withExposedPorts(8123)
            .waitingFor(Wait.forHttp("/ping").forPort(8123).withStartupTimeout(Duration.ofMinutes(2)));

    static final GenericContainer<?> elastic = new GenericContainer<>("elasticsearch:8.15.3")
            .withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            // a build machine's disk is often over Elasticsearch's 90 % watermark (the Oracle test image alone is
            // ~6 GB); above it new indices get no shard and every write times out
            .withEnv("cluster.routing.allocation.disk.threshold_enabled", "false")
            .withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/_cluster/health?wait_for_status=yellow").forPort(9200)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws Exception {
        Startables.deepStart(clickhouse, elastic).join();
        for (String statement : statements(resource("/clickhouse/yapi_logs.sql"))) {
            clickhouseQuery(statement);
        }
        send("PUT", elasticUrl() + "/_component_template/yapi-fields", resource("/elastic/yapi-fields.component-template.json"));
        send("PUT", elasticUrl() + "/_index_template/requests", """
                {"index_patterns": ["requests_*"], "composed_of": ["yapi-fields"], "priority": 100}
                """);
    }

    @AfterAll
    static void stop() {
        clickhouse.stop();
        elastic.stop();
    }

    @Test
    void theClickHouseSchemaTakesTheSinkRows() throws Exception {
        ClickHouseWriter writer = new ClickHouseWriter(new SinkProperties.ClickHouse(true, clickhouseUrl(), null,
                "yapi", "yapi", null));
        String id = Events.newRequestId();
        LogEvent broken = Events.request("not-a-uuid", "order-service");

        List<Rejection> rejected = writer.write(List.of(Events.request(id, "order-service"), Events.response(id), broken));

        assertThat(rejected).extracting(Rejection::index).containsExactly(2);
        assertThat(clickhouseQuery("SELECT count() FROM yapi_logs.yapi_log FINAL WHERE request_id = '" + id + "'"))
                .isEqualTo("2");
        assertThat(clickhouseQuery("SELECT outcome, status_code, duration_ms, toString(event_time) FROM yapi_logs.yapi_log "
                + "WHERE request_id = '" + id + "' AND event_type = 'RESPONSE'"))
                .isEqualTo("SUCCESS\t201\t87\t2026-10-08 11:28:41.512");
        assertThat(clickhouseQuery("SELECT countMerge(requests) FROM yapi_logs.yapi_metrics_1m "
                + "WHERE maintask = 'order-service' AND outcome = 'SUCCESS'")).isEqualTo("1");

        // a replayed event is a duplicate row until ReplacingMergeTree merges it away
        writer.write(List.of(Events.response(id)));
        assertThat(clickhouseQuery("SELECT count() FROM yapi_logs.yapi_log FINAL WHERE request_id = '" + id + "'"))
                .isEqualTo("2");
    }

    @Test
    void elasticBuildsTheLegacyDocumentInAnyOrder() throws Exception {
        ElasticBulkWriter writer = new ElasticBulkWriter(new SinkProperties.Elastic(true, elasticUrl(), null, null,
                null, null, null, null, null));
        String id = Events.newRequestId();

        assertThat(writer.write(List.of(Events.response(id)))).isEmpty();
        assertThat(writer.write(List.of(Events.request(id, "order-service"), Events.response(id)))).isEmpty();
        send("POST", elasticUrl() + "/requests_2026-10/_refresh", "");

        JsonNode document = Json.parse(send("GET", elasticUrl() + "/requests_2026-10/_doc/" + id, null)).get("_source");
        assertThat(document.get("maintask").stringValue()).isEqualTo("order-service");
        assertThat(document.get("password").stringValue()).isEqualTo("xxxx");
        assertThat(document.get("data_response").get("BACKEND_STATUS").intValue()).isEqualTo(201);
        assertThat(document.get("yapi").get("received_at").stringValue()).isEqualTo("2026-10-08T11:28:41.425Z");
        assertThat(document.get("yapi").get("responded_at").stringValue()).isEqualTo("2026-10-08T11:28:41.512Z");
        JsonNode count = Json.parse(send("POST", elasticUrl() + "/requests_2026-10/_count", """
                {"query": {"term": {"query_id.keyword": "b1f4c2d8-6e3a-4f5b-9c7d-2a8e0f1b3c4d"}}}
                """));
        assertThat(count.get("count").longValue()).isGreaterThanOrEqualTo(2);
        JsonNode mapping = Json.parse(send("GET", elasticUrl() + "/requests_2026-10/_mapping/field/yapi.received_at,yapi.status_code", null));
        String mappings = mapping.toString();
        assertThat(mappings).contains("\"type\":\"date\"").contains("\"type\":\"short\"");
    }

    @Test
    void aDocumentElasticCannotMapIsRejected() throws Exception {
        ElasticBulkWriter writer = new ElasticBulkWriter(new SinkProperties.Elastic(true, elasticUrl(), null, null,
                null, null, null, null, null));
        LogEvent first = withBody(Events.request(Events.newRequestId(), "order-service"), "tutar", Json.number(10));
        LogEvent clashing = withBody(Events.request(Events.newRequestId(), "order-service"), "tutar",
                Json.string("on lira"));

        assertThat(writer.write(List.of(first))).isEmpty();
        List<Rejection> rejected = writer.write(List.of(clashing));

        assertThat(rejected).extracting(Rejection::index).containsExactly(0);
        assertThat(rejected.getFirst().reason()).startsWith("Elastic 400");
    }

    @Test
    void aLoggedResponseDataTextOf40KbIsAcceptedByTheDefaultMapping() throws Exception {
        ElasticBulkWriter writer = new ElasticBulkWriter(new SinkProperties.Elastic(true, elasticUrl(), null, null,
                null, null, null, null, null));
        String id = Events.newRequestId();
        ObjectNode data = Json.object();
        data.put("kayitlar", "müşteri kaydı; ".repeat(2_600));
        String text = Json.write(data);
        assertThat(Json.utf8Length(text)).isGreaterThan(40_000);
        ObjectNode clientResponse = Json.object();
        clientResponse.put("BACKEND_STATUS", 200);
        clientResponse.put("status", 200);
        clientResponse.put("data", text);
        LogEvent response = Events.response(id);
        LogEvent logged = new LogEvent(response.requestId(), response.traceId(), response.eventType(),
                response.eventTime(), response.environment(), response.maintask(), response.subtask(),
                response.routeVersion(), response.responseProfile(), response.responseProfileVersion(),
                response.application(), response.uid(), response.userId(), response.queryId(), response.permList(),
                response.token(), response.sessionOperation(), response.clientIp(), response.userAgent(),
                response.httpMethod(), response.targetPath(), response.statusCode(), response.outcome(),
                response.durationMs(), response.body(), response.bodySize(), true, clientResponse,
                response.errorMessage(), response.yapiInstance());

        assertThat(writer.write(List.of(Events.request(id, "order-service"), logged))).isEmpty();
        send("POST", elasticUrl() + "/requests_2026-10/_refresh", "");

        JsonNode document = Json.parse(send("GET", elasticUrl() + "/requests_2026-10/_doc/" + id, null)).get("_source");
        assertThat(Json.text(document.get("data_response").get("data"))).isEqualTo(text);
        assertThat(document.get("yapi").get("body_truncated").booleanValue()).isTrue();
    }

    /** The event with one more field in the frontend body. */
    private static LogEvent withBody(LogEvent event, String field, JsonNode value) {
        ObjectNode body = (ObjectNode) event.body().deepCopy();
        body.set(field, value);
        return new LogEvent(event.requestId(), event.traceId(), event.eventType(), event.eventTime(),
                event.environment(), event.maintask(), event.subtask(), event.routeVersion(), event.responseProfile(),
                event.responseProfileVersion(), event.application(), event.uid(), event.userId(), event.queryId(),
                event.permList(), event.token(), event.sessionOperation(), event.clientIp(), event.userAgent(),
                event.httpMethod(), event.targetPath(), event.statusCode(), event.outcome(), event.durationMs(), body,
                event.bodySize(), event.bodyTruncated(), event.clientResponse(), event.errorMessage(),
                event.yapiInstance());
    }

    private static String clickhouseUrl() {
        return "http://" + clickhouse.getHost() + ":" + clickhouse.getMappedPort(8123);
    }

    private static String elasticUrl() {
        return "http://" + elastic.getHost() + ":" + elastic.getMappedPort(9200);
    }

    private static String clickhouseQuery(String sql) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(clickhouseUrl() + "/?user=yapi&password=yapi"))
                .POST(HttpRequest.BodyPublishers.ofString(sql, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(sql + "\n" + response.body()).isEqualTo(200);
        return response.body().strip();
    }

    private static String send(String method, String url, String json) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        if (json != null) {
            request.header("Content-Type", "application/json");
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(url + "\n" + response.body()).isBetween(200, 299);
        return response.body();
    }

    /** Statements of a SQL file, comments removed. */
    static List<String> statements(String sql) {
        StringBuilder withoutComments = new StringBuilder();
        for (String line : sql.split("\n")) {
            int comment = line.indexOf("--");
            withoutComments.append(comment >= 0 ? line.substring(0, comment) : line).append('\n');
        }
        return java.util.Arrays.stream(withoutComments.toString().split(";"))
                .map(String::strip).filter(statement -> !statement.isEmpty()).toList();
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = RealTargetsTest.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
