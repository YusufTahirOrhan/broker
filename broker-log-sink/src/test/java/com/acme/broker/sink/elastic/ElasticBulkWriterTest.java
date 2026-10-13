package com.acme.broker.sink.elastic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.acme.broker.core.json.Json;
import com.acme.broker.sink.config.SinkProperties;
import com.acme.broker.sink.fake.Events;
import com.acme.broker.sink.kafka.Rejection;
import com.acme.broker.sink.kafka.SinkUnavailableException;

import tools.jackson.databind.JsonNode;

class ElasticBulkWriterTest {

    private final ElasticBulkWriter writer = new ElasticBulkWriter(
            new SinkProperties.Elastic(true, "http://localhost:9200", null, null, null, null, null, null, null));

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
