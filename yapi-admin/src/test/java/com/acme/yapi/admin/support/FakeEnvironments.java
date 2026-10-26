package com.acme.yapi.admin.support;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.testing.CoreFixtures;
import com.acme.yapi.core.testing.FakeHttpServer;
import com.sun.net.httpserver.HttpExchange;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The three environments' Admin APIs in memory, behind one server ({@code /dev}, {@code /preprod}, {@code /prod}), with
 * the behaviour the central admin relies on: promotion IDs make applies idempotent and refuse older requests, a
 * route needs its project and profile in the environment.
 */
public final class FakeEnvironments implements AutoCloseable {

    public static final class Environment {
        public final AtomicLong configVersion = new AtomicLong();
        public final Map<String, ObjectNode> projects = new ConcurrentHashMap<>();
        public final Map<String, ObjectNode> profiles = new ConcurrentHashMap<>();
        public final Map<String, ObjectNode> routes = new ConcurrentHashMap<>();
        public final Map<String, String> probeResults = new ConcurrentHashMap<>();
        public final List<JsonNode> testCalls = new CopyOnWriteArrayList<>();
        public volatile JsonNode spec = CoreFixtures.sampleSpec();
        public volatile boolean silent;
        /** Session records by uid, as the giriş service wrote them: token, client IP, user agent, permissions. */
        public final Map<String, List<String>> sessions = new ConcurrentHashMap<>();
        public final AtomicLong sessionChecks = new AtomicLong();

        void reset() {
            configVersion.set(0);
            projects.clear();
            profiles.clear();
            routes.clear();
            probeResults.clear();
            testCalls.clear();
            spec = CoreFixtures.sampleSpec();
            silent = false;
            sessions.clear();
            sessionChecks.set(0);
        }

        /** Stores a session record; the first three values are token, client IP and user agent. */
        public void session(String uid, String token, String clientIp, String userAgent, String... permissions) {
            List<String> record = new java.util.ArrayList<>(List.of(token, clientIp, userAgent));
            record.addAll(List.of(permissions));
            sessions.put(uid, record);
        }
    }

    private final FakeHttpServer server = FakeHttpServer.start();
    private final Map<String, Environment> environments = new ConcurrentHashMap<>(Map.of(
            "dev", new Environment(), "preprod", new Environment(), "prod", new Environment()));

    public FakeEnvironments() {
        server.otherwise(this::dispatch);
    }

    public String url(String environment) {
        return server.baseUrl() + "/" + environment.toLowerCase(Locale.ROOT);
    }

    public Environment get(String environment) {
        return environments.get(environment.toLowerCase(Locale.ROOT));
    }

    public void reset() {
        environments.values().forEach(Environment::reset);
    }

    private void dispatch(HttpExchange exchange, FakeHttpServer.Recorded request) throws IOException {
        String[] parts = request.path().split("/");
        // "", env, "admin", "v1", ...
        Environment environment = parts.length > 1 ? environments.get(parts[1]) : null;
        if (environment == null || parts.length < 5 || !"admin".equals(parts[2])) {
            problem(exchange, 404, "NOT_FOUND", "yok");
            return;
        }
        if (environment.silent) {
            exchange.close();
            return;
        }
        String method = request.method();
        List<String> rest = List.of(parts).subList(4, parts.length);
        JsonNode body = request.body().length == 0 ? null : Json.parse(request.body());
        switch (method + " " + rest.getFirst()) {
            case "PUT projects" -> {
                if (rest.size() != 2) {
                    problem(exchange, 404, "NOT_FOUND", "yok");
                    return;
                }
                ObjectNode project = Json.object();
                project.set("baseUrl", body.get("baseUrl"));
                project.set("enabled", body.get("enabled"));
                project.set("alarmRoute", body.has("alarmRoute") ? body.get("alarmRoute") : Json.nullNode());
                ObjectNode previous = environment.projects.put(rest.get(1), project);
                boolean changed = previous == null || !previous.equals(project);
                ObjectNode answer = Json.object();
                answer.put("maintask", rest.get(1));
                answer.put("changed", changed);
                answer.put("configVersion", changed ? environment.configVersion.incrementAndGet()
                        : environment.configVersion.get());
                json(exchange, 200, answer);
            }
            case "GET projects" -> {
                if (!environment.projects.containsKey(rest.get(1))) {
                    problem(exchange, 404, "PROJECT_NOT_FOUND", "Proje bu ortamda tanımlı değil");
                    return;
                }
                json(exchange, 200, environment.spec);
            }
            case "PUT response-profiles" -> apply(exchange, environment, environment.profiles, rest.get(1), body);
            case "PUT routes" -> {
                String key = rest.get(1) + "/" + rest.get(2);
                if (!environment.projects.containsKey(rest.get(1))) {
                    problem(exchange, 409, "PROJECT_NOT_FOUND", "Proje bu ortamda tanımlı değil");
                    return;
                }
                String profile = Json.stringField(body, "responseProfile");
                if (profile != null && !environment.profiles.containsKey(profile)) {
                    problem(exchange, 409, "PROFILE_NOT_FOUND", "Yanıt profili bu ortamda yok");
                    return;
                }
                ((ObjectNode) body).put("status", "ACTIVE");
                apply(exchange, environment, environment.routes, key, body);
            }
            case "POST routes" -> {
                String key = rest.get(1) + "/" + rest.get(2);
                ObjectNode route = environment.routes.get(key);
                if (route == null) {
                    problem(exchange, 404, "ROUTE_NOT_FOUND", "Route bu ortamda yok");
                    return;
                }
                ObjectNode disabled = route.deepCopy();
                disabled.put("status", "DISABLED");
                disabled.set("promotionId", body.get("promotionId"));
                apply(exchange, environment, environment.routes, key, disabled);
            }
            case "GET routes" -> {
                ObjectNode listing = Json.object();
                listing.put("configVersion", environment.configVersion.get());
                ArrayNode routes = listing.putArray("routes");
                environment.routes.forEach((key, route) -> {
                    ObjectNode item = routes.addObject();
                    item.put("maintask", key.substring(0, key.indexOf('/')));
                    item.put("subtask", key.substring(key.indexOf('/') + 1));
                    item.set("version", route.get("version"));
                    item.set("status", route.get("status"));
                });
                json(exchange, 200, listing);
            }
            case "GET cache-status" -> {
                ObjectNode status = Json.object();
                status.put("configVersion", environment.configVersion.get());
                status.put("configUpdatedAt", Instant.now().minusSeconds(120).toString());
                ArrayNode instances = status.putArray("instances");
                instances.addObject().put("instanceId", "yapi-1").put("loadedVersion", environment.configVersion.get())
                        .put("loadedAt", Instant.now().toString());
                instances.addObject().put("instanceId", "yapi-2").put("loadedVersion", environment.configVersion.get() - 1)
                        .put("loadedAt", Instant.now().toString());
                json(exchange, 200, status);
            }
            case "POST probe" -> {
                String key = Json.stringField(body, "httpMethod") + " " + Json.stringField(body, "pathTemplate");
                ObjectNode result = Json.object();
                result.put("result", environment.probeResults.getOrDefault(key, "OK"));
                result.put("status", 200);
                result.put("message", "test");
                json(exchange, 200, result);
            }
            case "POST sessions" -> {
                environment.sessionChecks.incrementAndGet();
                List<String> record = environment.sessions.get(Json.stringField(body, "uid"));
                boolean valid = record != null && record.get(0).equals(Json.stringField(body, "token"))
                        && record.get(1).equals(Json.stringField(body, "clientIp"))
                        && record.get(2).equals(Json.stringField(body, "userAgent"));
                ObjectNode answer = Json.object();
                answer.put("valid", valid);
                ArrayNode permissions = answer.putArray("permissions");
                if (valid) {
                    record.subList(3, record.size()).forEach(permissions::add);
                }
                json(exchange, 200, answer);
            }
            case "POST test" -> {
                environment.testCalls.add(body);
                ObjectNode result = Json.object();
                result.put("outcome", "SUCCESS");
                result.put("interpretation", "Çalışıyor");
                result.set("received", body);
                json(exchange, 200, result);
            }
            default -> problem(exchange, 404, "NOT_FOUND", "yok: " + method + " " + request.path());
        }
    }

    private static void apply(HttpExchange exchange, Environment environment, Map<String, ObjectNode> table,
                              String key, JsonNode body) throws IOException {
        long promotion = body.get("promotionId").longValue();
        ObjectNode current = table.get(key);
        ObjectNode answer = Json.object();
        if (current == null || promotion > current.get("promotionId").longValue()) {
            table.put(key, (ObjectNode) body.deepCopy());
            answer.put("applied", true);
            answer.put("configVersion", environment.configVersion.incrementAndGet());
            answer.put("alreadyApplied", false);
            json(exchange, 200, answer);
        } else if (promotion == current.get("promotionId").longValue()) {
            answer.put("applied", true);
            answer.put("configVersion", environment.configVersion.get());
            answer.put("alreadyApplied", true);
            json(exchange, 200, answer);
        } else {
            problem(exchange, 409, "STALE_PROMOTION", "Ortamda daha yeni bir talep uygulanmış");
        }
    }

    private static void json(HttpExchange exchange, int status, JsonNode body) throws IOException {
        FakeHttpServer.respond(exchange, status, Map.of("Content-Type", "application/json"), Json.writeBytes(body));
    }

    private static void problem(HttpExchange exchange, int status, String code, String detail) throws IOException {
        ObjectNode body = Json.object();
        body.put("type", "about:blank");
        body.put("status", status);
        body.put("code", code);
        body.put("detail", detail);
        body.putArray("errors");
        FakeHttpServer.respond(exchange, status, Map.of("Content-Type", "application/problem+json"),
                Json.writeBytes(body));
    }

    static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        server.close();
    }
}
