package com.acme.yapi.sink.elastic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.acme.yapi.core.ids.Ids;
import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.logging.LogEvent;
import com.acme.yapi.sink.config.SinkProperties;
import com.acme.yapi.sink.fake.Events;
import com.acme.yapi.sink.kafka.Rejection;
import com.acme.yapi.sink.kafka.SinkUnavailableException;

import tools.jackson.databind.JsonNode;

class ElasticBulkWriterTest {

    private final ElasticBulkWriter writer = new ElasticBulkWriter(
            new SinkProperties.Elastic(true, "http://localhost:9200", null, null, null, null, null, null, null, null, null));

    @Test
    void mainDocumentsAreUpsertedAndParameterDocumentsIndexed() {
        String id = Events.newRequestId();
        ElasticBulkWriter.Bulk bulk = writer.bulk(List.of(Events.request(id, "order-service"), Events.response(id)));

        List<JsonNode> lines = bulk.body().lines().map(Json::parse).toList();
        // REQUEST: main upsert + two parameter documents (adres.il, adres.ilce); RESPONSE: main upsert
        assertThat(bulk.owners()).containsExactly(0, 0, 0, 1);
        JsonNode update = lines.get(0).get("update");
        assertThat(update.get("_index").stringValue()).isEqualTo("requests_2026-10");
        assertThat(update.get("_id").stringValue()).isEqualTo(id);
        assertThat(update.get("retry_on_conflict").intValue()).isEqualTo(3);
        assertThat(lines.get(1).get("doc_as_upsert").booleanValue()).isTrue();
        assertThat(lines.get(1).get("doc").get("request_id").stringValue()).isEqualTo(id);
        assertThat(lines.get(2).has("index")).isTrue();
        assertThat(lines.get(3).get("adres").get("parameter_name").stringValue()).isEqualTo("il");
        assertThat(lines.get(6).get("update").get("_id").stringValue()).isEqualTo(id);
        assertThat(lines.get(7).get("doc").has("data_response")).isTrue();
    }

    @Test
    void theMonthOfTheIndexIsTurkishTimeByDefault() {
        // 22:30 UTC on the last day of September is already October in Istanbul, where the old broker runs
        String id = Ids.uuidV7(Instant.parse("2026-09-30T22:30:00Z").toEpochMilli()).toString();

        JsonNode turkish = Json.parse(writer.bulk(List.of(Events.response(id))).body().lines().findFirst().orElseThrow());
        ElasticBulkWriter utcWriter = new ElasticBulkWriter(new SinkProperties.Elastic(true, "http://localhost:9200",
                null, ZoneId.of("UTC"), null, null, null, null, null, null, null));
        JsonNode utc = Json.parse(utcWriter.bulk(List.of(Events.response(id))).body().lines().findFirst().orElseThrow());

        assertThat(turkish.get("update").get("_index").stringValue()).isEqualTo("requests_2026-10");
        assertThat(utc.get("update").get("_index").stringValue()).isEqualTo("requests_2026-09");
    }

    @Test
    void alarmsGoToTheConfiguredAlarmIndex() {
        LogEvent alarmed = Events.alarmed(Events.newRequestId());
        ElasticBulkWriter custom = new ElasticBulkWriter(new SinkProperties.Elastic(true, "http://localhost:9200",
                null, null, null, null, null, null, null, "alarm_test", null));

        List<JsonNode> lines = custom.bulk(List.of(alarmed)).body().lines().map(Json::parse).toList();
        JsonNode index = lines.get(2).get("index");
        assertThat(index.get("_index").stringValue()).isEqualTo("alarm_test");
        assertThat(index.get("_id").stringValue()).isEqualTo(alarmed.queryId());
        assertThat(lines.get(3).get("yapi").get("alarms").get(0).stringValue()).isEqualTo("10000000146");
        assertThat(writer.bulk(List.of(alarmed)).body().lines().skip(2).findFirst().map(Json::parse).orElseThrow()
                .get("index").get("_index").stringValue()).isEqualTo("alarm");
    }

    @Test
    void refusedRequestsGoToTheConfiguredUnauthorizedIndex() {
        LogEvent denied = Events.denied(Events.newRequestId());
        ElasticBulkWriter custom = new ElasticBulkWriter(new SinkProperties.Elastic(true, "http://localhost:9200",
                null, null, null, null, null, null, null, null, "refused_test"));

        List<JsonNode> lines = custom.bulk(List.of(denied)).body().lines().map(Json::parse).toList();
        JsonNode index = lines.get(2).get("index");
        assertThat(index.get("_index").stringValue()).isEqualTo("refused_test");
        assertThat(index.get("_id").stringValue()).isEqualTo(denied.requestId());
        assertThat(lines.get(3).get("denied_reason").stringValue()).isEqualTo("PERMISSION");
        assertThat(lines.get(3).get("body").isString()).isTrue();
        assertThat(writer.bulk(List.of(denied)).body().lines().skip(2).findFirst().map(Json::parse).orElseThrow()
                .get("index").get("_index").stringValue()).isEqualTo("yapi_unauthorized");
    }

    @Test
    void itemFailuresAreSortedIntoRetryAndReject() {
        JsonNode refused = Json.parse("""
                {"errors": true, "items": [
                  {"update": {"_id": "a", "status": 200}},
                  {"index": {"_id": "b", "status": 400, "error": {"type": "mapper_parsing_exception"}}},
                  {"index": {"_id": "c", "status": 400, "error": {"type": "mapper_parsing_exception"}}}
                ]}
                """);
        assertThat(ElasticBulkWriter.rejections(refused, List.of(0, 1, 1)))
                .extracting(Rejection::index).containsExactly(1);

        JsonNode busy = Json.parse("""
                {"errors": true, "items": [{"update": {"_id": "a", "status": 429}}]}
                """);
        assertThatThrownBy(() -> ElasticBulkWriter.rejections(busy, List.of(0)))
                .isInstanceOf(SinkUnavailableException.class);
    }
}
