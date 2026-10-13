package com.acme.broker.sink.fake;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import com.acme.broker.core.json.Json;
import com.acme.broker.core.testing.FakeHttpServer;

import tools.jackson.databind.JsonNode;

/**
 * ClickHouse's HTTP interface for {@code INSERT ... FORMAT JSONEachRow}: an insert is all or nothing, and a row it
 * cannot parse fails the whole insert with 400.
 */
public final class FakeClickHouse implements AutoCloseable {

    private final FakeHttpServer server = FakeHttpServer.start();
    private final List<JsonNode> rows = new CopyOnWriteArrayList<>();
    private final List<String> queries = new CopyOnWriteArrayList<>();
    private final AtomicBoolean down = new AtomicBoolean();
    private volatile String poison = "\u0000never";

    public FakeClickHouse() {
        server.on("POST", "/", (exchange, request) -> {
            queries.add(URLDecoder.decode(request.query(), StandardCharsets.UTF_8));
            if (down.get()) {
                FakeHttpServer.respond(exchange, 503, Map.of(), "Code: 210. Connection refused".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String body = request.bodyText();
            if (body.contains(poison)) {
                FakeHttpServer.respond(exchange, 400, Map.of(),
                        "Code: 27. DB::Exception: Cannot parse input".getBytes(StandardCharsets.UTF_8));
                return;
            }
            body.lines().filter(line -> !line.isBlank()).forEach(line -> rows.add(Json.parse(line)));
            FakeHttpServer.respond(exchange, 200, Map.of(), null);
        });
    }

    public String url() {
        return server.baseUrl();
    }

    public List<JsonNode> rows(String requestId) {
        return rows.stream().filter(row -> requestId.equals(Json.stringField(row, "request_id"))).toList();
    }

    public List<String> queries() {
        return List.copyOf(queries);
    }

    public void down(boolean value) {
        down.set(value);
    }

    /** Rows containing this text are refused with 400. */
    public void poison(String text) {
        poison = text;
    }

    @Override
    public void close() {
        server.close();
    }
}
