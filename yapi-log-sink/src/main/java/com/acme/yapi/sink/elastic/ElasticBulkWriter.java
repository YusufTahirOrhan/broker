package com.acme.yapi.sink.elastic;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.legacy.ElasticOperation;
import com.acme.yapi.core.legacy.LegacyDocuments;
import com.acme.yapi.core.logging.LogEvent;
import com.acme.yapi.sink.config.SinkProperties;
import com.acme.yapi.sink.kafka.Rejection;
import com.acme.yapi.sink.kafka.SinkUnavailableException;
import com.acme.yapi.sink.kafka.SinkWriter;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes the old broker's documents with one {@code _bulk} call per batch. Main documents use {@code update} with
 * {@code doc_as_upsert}: REQUEST and RESPONSE may arrive in any order and a replayed event leaves the same document.
 * Parameter documents use {@code index} under a name-based ID.
 */
public class ElasticBulkWriter implements SinkWriter {

    private final HttpClient client;
    private final SinkProperties.Elastic settings;
    private final LegacyDocuments documents;
    private final String authorization;

    public ElasticBulkWriter(SinkProperties.Elastic settings) {
        this.settings = settings;
        this.documents = new LegacyDocuments(settings.indexPrefix(), settings.indexZone());
        this.client = HttpClient.newBuilder().connectTimeout(settings.timeout()).build();
        if (settings.apiKey() != null && !settings.apiKey().isBlank()) {
            authorization = "ApiKey " + settings.apiKey();
        } else if (settings.username() != null && !settings.username().isBlank()) {
            authorization = "Basic " + Base64.getEncoder().encodeToString((settings.username() + ":"
                    + (settings.password() == null ? "" : settings.password())).getBytes(StandardCharsets.UTF_8));
        } else {
            authorization = null;
        }
    }

    @Override
    public String name() {
        return "elastic";
    }

    /** The bulk body and, for every operation line, the index of the event it came from. */
    record Bulk(String body, List<Integer> owners) {
    }

    Bulk bulk(List<LogEvent> events) {
        StringBuilder body = new StringBuilder();
        List<Integer> owners = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            for (ElasticOperation operation : documents.operations(events.get(i))) {
                ObjectNode action = Json.object();
                ObjectNode meta = action.putObject(operation.kind() == ElasticOperation.Kind.UPSERT ? "update" : "index");
                meta.put("_index", operation.index());
                meta.put("_id", operation.id());
                if (operation.kind() == ElasticOperation.Kind.UPSERT) {
                    meta.put("retry_on_conflict", settings.retryOnConflict());
                    ObjectNode source = Json.object();
                    source.set("doc", operation.document());
                    source.put("doc_as_upsert", true);
                    body.append(Json.write(action)).append('\n').append(Json.write(source)).append('\n');
                } else {
                    body.append(Json.write(action)).append('\n').append(Json.write(operation.document())).append('\n');
                }
                owners.add(i);
            }
        }
        return new Bulk(body.toString(), owners);
    }

    @Override
    public List<Rejection> write(List<LogEvent> events) {
        Bulk bulk = bulk(events);
        if (bulk.owners().isEmpty()) {
            return List.of();
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(trim(settings.url()) + "/_bulk"))
                .timeout(settings.timeout())
                .header("Content-Type", "application/x-ndjson")
                .POST(HttpRequest.BodyPublishers.ofString(bulk.body(), StandardCharsets.UTF_8));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        HttpResponse<byte[]> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new SinkUnavailableException("Elastic'e ulaşılamadı: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SinkUnavailableException("Elastic çağrısı kesildi", e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new SinkUnavailableException("Elastic _bulk " + response.statusCode() + " döndü: "
                    + new String(response.body(), StandardCharsets.UTF_8));
        }
        return rejections(Json.parse(response.body()), bulk.owners());
    }

    /**
     * Item failures: 429 and 5xx mean the cluster is busy and the batch is retried (writes are idempotent); any other
     * failure is a document Elastic will never accept, so its event goes to the DLQ.
     */
    static List<Rejection> rejections(JsonNode response, List<Integer> owners) {
        JsonNode errors = response.get("errors");
        if (errors == null || !errors.booleanValue()) {
            return List.of();
        }
        Map<Integer, String> rejected = new LinkedHashMap<>();
        JsonNode items = response.get("items");
        for (int i = 0; items != null && i < items.size(); i++) {
            JsonNode item = items.get(i);
            JsonNode result = item.get(Json.fieldNames(item).getFirst());
            int status = result.get("status").intValue();
            if (status < 300) {
                continue;
            }
            if (status == 429 || status >= 500) {
                throw new SinkUnavailableException("Elastic geçici hata döndü: " + status);
            }
            rejected.putIfAbsent(owners.get(i), "Elastic " + status + ": " + Json.text(result.get("error")));
        }
        List<Rejection> result = new ArrayList<>();
        rejected.forEach((index, reason) -> result.add(new Rejection(index, reason)));
        return result;
    }

    private static String trim(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
