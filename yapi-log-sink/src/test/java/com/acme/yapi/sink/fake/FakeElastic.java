package com.acme.yapi.sink.fake;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.testing.FakeHttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Elasticsearch's {@code _bulk} as far as the sink uses it: {@code update} with {@code doc_as_upsert} merges the
 * partial document into the stored one at every object level, {@code index} replaces it.
 */
public final class FakeElastic implements AutoCloseable {

    private final FakeHttpServer server = FakeHttpServer.start();
    private final Map<String, ObjectNode> documents = new ConcurrentHashMap<>();
    private final Set<String> rejectedIds = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean down = new AtomicBoolean();
    public final AtomicInteger bulkCalls = new AtomicInteger();

    public FakeElastic() {
        server.on("POST", "/_bulk", (exchange, request) -> {
            bulkCalls.incrementAndGet();
            if (down.get()) {
                FakeHttpServer.respond(exchange, 503, Map.of("Content-Type", "application/json"),
                        "{\"error\": \"unavailable\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            FakeHttpServer.respond(exchange, 200, Map.of("Content-Type", "application/json"),
                    Json.writeBytes(apply(request.bodyText())));
        });
    }

    private ObjectNode apply(String body) {
        List<String> lines = body.lines().filter(line -> !line.isBlank()).toList();
        ObjectNode response = Json.object();
        ArrayNode items = response.putArray("items");
        boolean errors = false;
        for (int i = 0; i < lines.size(); i += 2) {
            JsonNode action = Json.parse(lines.get(i));
            JsonNode source = Json.parse(lines.get(i + 1));
            String kind = Json.fieldNames(action).getFirst();
            JsonNode meta = action.get(kind);
            String id = Json.stringField(meta, "_id");
            String key = Json.stringField(meta, "_index") + "/" + id;
            ObjectNode result = items.addObject().putObject(kind);
            result.put("_index", Json.stringField(meta, "_index"));
            result.put("_id", id);
            if (rejectedIds.contains(id)) {
                errors = true;
                result.put("status", 400);
                result.putObject("error").put("type", "mapper_parsing_exception").put("reason", "test");
                continue;
            }
            if (kind.equals("update")) {
                ObjectNode current = documents.get(key);
                ObjectNode merged = current == null ? Json.object() : (ObjectNode) current.deepCopy();
                merge(merged, (ObjectNode) source.get("doc"));
                documents.put(key, merged);
                result.put("status", current == null ? 201 : 200);
            } else {
                documents.put(key, (ObjectNode) source.deepCopy());
                result.put("status", 201);
            }
        }
        response.put("errors", errors);
        return response;
    }

    private static void merge(ObjectNode target, ObjectNode patch) {
        for (Map.Entry<String, JsonNode> entry : Json.fields(patch)) {
            JsonNode existing = target.get(entry.getKey());
            if (existing != null && existing.isObject() && entry.getValue().isObject()) {
                merge((ObjectNode) existing, (ObjectNode) entry.getValue());
            } else {
                target.set(entry.getKey(), entry.getValue().deepCopy());
            }
        }
    }

    public String url() {
        return server.baseUrl();
    }

    public ObjectNode document(String index, String id) {
        return documents.get(index + "/" + id);
    }

    public Map<String, ObjectNode> documents() {
        return Map.copyOf(documents);
    }

    public void down(boolean value) {
        down.set(value);
    }

    public void reject(String id) {
        rejectedIds.add(id);
    }

    @Override
    public void close() {
        server.close();
    }
}
