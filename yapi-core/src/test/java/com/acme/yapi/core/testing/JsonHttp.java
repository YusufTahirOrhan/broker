package com.acme.yapi.core.testing;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import com.acme.yapi.core.json.Json;

import tools.jackson.databind.JsonNode;

/** A small JSON client for calling a running application in tests. */
public final class JsonHttp {

    public record Response(int status, Map<String, java.util.List<String>> headers, byte[] bytes) {

        public JsonNode json() {
            return Json.parse(bytes);
        }

        public String text() {
            return new String(bytes, StandardCharsets.UTF_8);
        }

        public String header(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .map(entry -> entry.getValue().getFirst())
                    .findFirst().orElse(null);
        }
    }

    private final HttpClient client;
    private final String baseUrl;

    public JsonHttp(String baseUrl) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), baseUrl);
    }

    public JsonHttp(HttpClient client, String baseUrl) {
        this.client = client;
        this.baseUrl = baseUrl;
    }

    public Response get(String path) {
        return send("GET", path, null, Map.of());
    }

    public Response put(String path, String json) {
        return send("PUT", path, json, Map.of());
    }

    public Response post(String path, String json) {
        return send("POST", path, json, Map.of());
    }

    public Response send(String method, String path, String json, Map<String, String> headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        if (json != null) {
            request.header("Content-Type", "application/json");
        }
        headers.forEach(request::header);
        try {
            HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Response(response.statusCode(), response.headers().map(), response.body());
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
