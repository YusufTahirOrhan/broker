package com.acme.yapi.sink.clickhouse;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.logging.LogEvent;
import com.acme.yapi.sink.config.SinkProperties;
import com.acme.yapi.sink.kafka.Rejection;
import com.acme.yapi.sink.kafka.SinkUnavailableException;
import com.acme.yapi.sink.kafka.SinkWriter;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Inserts events into {@code yapi_logs.yapi_log} with {@code INSERT ... FORMAT JSONEachRow} over HTTP, one insert
 * per batch. Columns that do not apply are written with their default, so no column is ever NULL. A batch with a row
 * ClickHouse cannot parse (HTTP 400) is inserted row by row to find it; ReplacingMergeTree removes duplicates.
 */
public class ClickHouseWriter implements SinkWriter {

    private final HttpClient client;
    private final SinkProperties.ClickHouse settings;
    private final URI insertUri;

    public ClickHouseWriter(SinkProperties.ClickHouse settings) {
        this.settings = settings;
        this.client = HttpClient.newBuilder().connectTimeout(settings.timeout()).build();
        String query = "INSERT INTO " + settings.table() + " FORMAT JSONEachRow";
        String base = settings.url().endsWith("/") ? settings.url() : settings.url() + "/";
        this.insertUri = URI.create(base + "?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&date_time_input_format=best_effort");
    }

    @Override
    public String name() {
        return "clickhouse";
    }

    /** One {@code yapi_log} row; the token, permissions and the frontend's response are not stored. */
    public static ObjectNode row(LogEvent event) {
        ObjectNode row = Json.object();
        row.put("event_time", event.eventTime().toString());
        row.put("request_id", event.requestId());
        row.put("trace_id", text(event.traceId()));
        row.put("event_type", event.eventType().name());
        row.put("environment", text(event.environment()));
        row.put("maintask", text(event.maintask()));
        row.put("subtask", text(event.subtask()));
        row.put("route_version", number(event.routeVersion()));
        row.put("response_profile", text(event.responseProfile()));
        row.put("response_profile_version", number(event.responseProfileVersion()));
        row.put("user_id", text(event.userId()));
        row.put("client_ip", text(event.clientIp()));
        row.put("user_agent", text(event.userAgent()));
        row.put("application", text(event.application()));
        row.put("uid", text(event.uid()));
        row.put("http_method", text(event.httpMethod()));
        row.put("target_path", text(event.targetPath()));
        row.put("status_code", number(event.statusCode()));
        row.put("outcome", text(event.outcome()));
        row.put("denied_reason", event.denial() == null ? "" : event.denial().reason().name());
        row.put("duration_ms", event.durationMs() == null ? 0 : event.durationMs());
        row.put("body", body(event.body()));
        row.put("body_size", number(event.bodySize()));
        row.put("body_truncated", Boolean.TRUE.equals(event.bodyTruncated()));
        row.put("error_message", text(event.errorMessage()));
        row.put("yapi_instance", text(event.yapiInstance()));
        return row;
    }

    @Override
    public List<Rejection> write(List<LogEvent> events) {
        List<String> rows = new ArrayList<>(events.size());
        for (LogEvent event : events) {
            rows.add(Json.write(row(event)));
        }
        Result result = insert(rows);
        if (result.ok()) {
            return List.of();
        }
        if (!result.badData()) {
            throw new SinkUnavailableException("ClickHouse insert başarısız: " + result.message());
        }
        List<Rejection> rejections = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Result single = rows.size() == 1 ? result : insert(List.of(rows.get(i)));
            if (single.ok()) {
                continue;
            }
            if (!single.badData()) {
                throw new SinkUnavailableException("ClickHouse insert başarısız: " + single.message());
            }
            rejections.add(new Rejection(i, "ClickHouse satırı kabul etmedi: " + single.message()));
        }
        return rejections;
    }

    private record Result(boolean ok, boolean badData, String message) {
    }

    private Result insert(List<String> rows) {
        HttpRequest.Builder request = HttpRequest.newBuilder(insertUri)
                .timeout(settings.timeout())
                .POST(HttpRequest.BodyPublishers.ofString(String.join("\n", rows) + "\n", StandardCharsets.UTF_8));
        if (settings.user() != null && !settings.user().isBlank()) {
            request.header("X-ClickHouse-User", settings.user());
            request.header("X-ClickHouse-Key", settings.password() == null ? "" : settings.password());
        }
        try {
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return new Result(true, false, null);
            }
            // ClickHouse answers 400 for input it cannot parse; auth (401/403), missing table (404) and server
            // errors are not the row's fault and are retried
            return new Result(false, status == 400, status + " " + response.body().strip());
        } catch (IOException e) {
            throw new SinkUnavailableException("ClickHouse'a ulaşılamadı: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SinkUnavailableException("ClickHouse çağrısı kesildi", e);
        }
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static int number(Integer value) {
        return value == null ? 0 : value;
    }

    private static String body(JsonNode body) {
        if (body == null || body.isNull() || body.isMissingNode()) {
            return "";
        }
        return body.isString() ? body.stringValue() : Json.write(body);
    }
}
