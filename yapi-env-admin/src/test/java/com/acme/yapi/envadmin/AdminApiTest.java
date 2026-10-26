package com.acme.yapi.envadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.runtime.RuntimeSignals;
import com.acme.yapi.core.session.SessionRecord;
import com.acme.yapi.core.session.SessionRecordCodec;
import com.acme.yapi.core.testcall.TestCallRequest;
import com.acme.yapi.core.testing.CoreFixtures;
import com.acme.yapi.core.testing.FakeHttpServer;
import com.acme.yapi.core.testing.JsonHttp;
import com.acme.yapi.envadmin.testing.RuntimeContainers;

import tools.jackson.databind.JsonNode;

@Tag("docker")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.ssl.enabled=false",
        "management.server.port=0",
        "yapi.env-admin.environment=PREPROD",
        "yapi.env-admin.test.enabled=true",
        "yapi.env-admin.test.secret=s3cret"
})
class AdminApiTest {

    static final FakeHttpServer service = FakeHttpServer.start();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        RuntimeContainers.register(registry);
        registry.add("yapi.env-admin.test.yapi-url", service::baseUrl);
    }

    @AfterAll
    static void stop() {
        service.close();
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    RedisConnectionFactory redisConnections;

    JsonHttp http;

    @BeforeEach
    void reset() {
        http = new JsonHttp("http://localhost:" + port);
        RuntimeContainers.reset();
        redis.delete(redis.keys(RuntimeSignals.LOADED_KEY_PREFIX + "*"));
        service.reset();
    }

    private long configVersion() {
        return jdbc.sql("SELECT version FROM runtime_config_version").query(Long.class).single();
    }

    private void project(String maintask, String baseUrl) {
        JsonHttp.Response response = http.put("/admin/v1/projects/" + maintask,
                "{\"baseUrl\": \"" + baseUrl + "\", \"enabled\": true, \"actor\": \"devops.ali\"}");
        assertThat(response.status()).as(response.text()).isEqualTo(200);
    }

    private static String route(long promotionId, int version, String profile) {
        return """
                {"promotionId": %d, "definitionId": 17, "version": %d, "httpMethod": "POST",
                 "pathTemplate": "/orders/{id}/notes", "permissions": ["ORDER_WRITE"], "anonymous": false,
                 "responseProfile": %s, "definition": %s, "actor": "sec.zeynep"}
                """.formatted(promotionId, version, profile == null ? "null" : "\"" + profile + "\"",
                CoreFixtures.ADD_ORDER_NOTE);
    }

    private static String profile(long promotionId, int version) {
        return """
                {"promotionId": %d, "profileId": 3, "version": %d, "definition": %s, "actor": "sec.zeynep"}
                """.formatted(promotionId, version, CoreFixtures.LOG_PROJECT_PROFILE);
    }

    private List<String> subscribe() {
        List<String> received = new CopyOnWriteArrayList<>();
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(redisConnections);
        container.addMessageListener((message, pattern) -> received.add(new String(message.getBody(),
                StandardCharsets.UTF_8)), new ChannelTopic(RuntimeSignals.ROUTES_CHANGED_CHANNEL));
        container.afterPropertiesSet();
        container.start();
        await().atMost(Duration.ofSeconds(5)).until(container::isListening);
        return received;
    }

    @Test
    void applyingARouteMovesTheConfigVersionAndSignalsBrokers() {
        List<String> signals = subscribe();
        project("order-service", service.baseUrl());
        long before = configVersion();

        JsonHttp.Response applied = http.put("/admin/v1/routes/order-service/add_order_note", route(100, 3, null));

        assertThat(applied.status()).as(applied.text()).isEqualTo(200);
        assertThat(applied.json().get("applied").booleanValue()).isTrue();
        assertThat(applied.json().get("alreadyApplied").booleanValue()).isFalse();
        assertThat(applied.json().get("configVersion").longValue()).isEqualTo(before + 1);
        await().atMost(Duration.ofSeconds(5)).until(() -> signals.contains(Long.toString(before + 1)));

        Map<String, Object> row = jdbc.sql("""
                        SELECT version, status, applied_by, promotion_id, permissions,
                               JSON_VALUE(definition, '$.request.pathVariables.id') AS id
                          FROM runtime_route WHERE maintask = 'order-service'
                        """).query().singleRow();
        assertThat(((Number) row.get("version")).intValue()).isEqualTo(3);
        assertThat(((Number) row.get("promotion_id")).longValue()).isEqualTo(100L);
        assertThat(row).containsEntry("status", "ACTIVE").containsEntry("applied_by", "sec.zeynep")
                .containsEntry("permissions", "[\"ORDER_WRITE\"]").containsEntry("id", "${request.siparisNo}");
    }

    @Test
    void projectWriteMovesTheVersionOnlyWhenSomethingChanged() {
        long before = configVersion();
        project("order-service", "http://order-service.preprod.svc:8080");
        assertThat(configVersion()).isEqualTo(before + 1);

        JsonHttp.Response same = http.put("/admin/v1/projects/order-service",
                "{\"baseUrl\": \"http://order-service.preprod.svc:8080\", \"actor\": \"devops.ali\"}");
        assertThat(same.json().get("changed").booleanValue()).isFalse();
        assertThat(configVersion()).isEqualTo(before + 1);

        JsonHttp.Response disabled = http.put("/admin/v1/projects/order-service",
                "{\"baseUrl\": \"http://order-service.preprod.svc:8080\", \"enabled\": false, \"actor\": \"devops.ali\"}");
        assertThat(disabled.json().get("changed").booleanValue()).isTrue();
        assertThat(disabled.json().get("enabled").booleanValue()).isFalse();
        assertThat(configVersion()).isEqualTo(before + 2);
    }

    @Test
    void theAlarmRouteOfAProjectIsPartOfItsSettings() {
        project("order-service", "http://order-service.preprod.svc:8080");
        long before = configVersion();
        String withAlarm = """
                {"baseUrl": "http://order-service.preprod.svc:8080", "actor": "devops.ali",
                 "alarmRoute": {"maintask": "guvenlik", "subtask": "alarm_kontrol"}}
                """;

        JsonHttp.Response set = http.put("/admin/v1/projects/order-service", withAlarm);
        assertThat(set.json().get("changed").booleanValue()).as(set.text()).isTrue();
        assertThat(set.json().get("alarmRoute").get("subtask").stringValue()).isEqualTo("alarm_kontrol");
        assertThat(jdbc.sql("SELECT alarm_maintask || '/' || alarm_subtask FROM runtime_project WHERE maintask = 'order-service'")
                .query(String.class).single()).isEqualTo("guvenlik/alarm_kontrol");
        assertThat(http.put("/admin/v1/projects/order-service", withAlarm).json().get("changed").booleanValue()).isFalse();
        assertThat(configVersion()).isEqualTo(before + 1);

        JsonHttp.Response cleared = http.put("/admin/v1/projects/order-service",
                "{\"baseUrl\": \"http://order-service.preprod.svc:8080\", \"actor\": \"devops.ali\"}");
        assertThat(cleared.json().get("changed").booleanValue()).isTrue();
        assertThat(cleared.json().get("alarmRoute").isNull()).isTrue();

        JsonHttp.Response invalid = http.put("/admin/v1/projects/order-service", """
                {"baseUrl": "http://order-service.preprod.svc:8080", "actor": "devops.ali",
                 "alarmRoute": {"maintask": "guvenlik"}}
                """);
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.json().get("errors").get(0).get("field").stringValue()).isEqualTo("alarmRoute.subtask");
    }

    @Test
    void baseUrlMustBeAPlainHttpAddress() {
        JsonHttp.Response response = http.put("/admin/v1/projects/order-service",
                "{\"baseUrl\": \"file:///etc/passwd\", \"actor\": \"devops.ali\"}");
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.header("Content-Type")).startsWith("application/problem+json");
        assertThat(response.json().get("code").stringValue()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.json().get("errors").get(0).get("field").stringValue()).isEqualTo("baseUrl");
    }

    @Test
    void aRetriedPromotionIsAcknowledgedWithoutWritingAgain() {
        project("order-service", service.baseUrl());
        long first = http.put("/admin/v1/routes/order-service/add_order_note", route(100, 3, null))
                .json().get("configVersion").longValue();

        JsonHttp.Response replay = http.put("/admin/v1/routes/order-service/add_order_note", route(100, 3, null));

        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.json().get("applied").booleanValue()).isTrue();
        assertThat(replay.json().get("alreadyApplied").booleanValue()).isTrue();
        assertThat(replay.json().get("configVersion").longValue()).isEqualTo(first);
        assertThat(configVersion()).isEqualTo(first);
    }

    @Test
    void definitionsLargerThanAVarcharAreStoredWhole() {
        project("order-service", service.baseUrl());
        String definition = CoreFixtures.largeAddOrderNote(40_000);
        String body = route(100, 3, null).replace(CoreFixtures.ADD_ORDER_NOTE, definition);

        JsonHttp.Response applied = http.put("/admin/v1/routes/order-service/add_order_note", body);

        assertThat(applied.status()).as(applied.text()).isEqualTo(200);
        String stored = jdbc.sql("SELECT definition FROM runtime_route").query(String.class).single();
        assertThat(stored.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(32 * 1024);
        assertThat(Json.parse(stored)).isEqualTo(Json.parse(definition));
    }

    @Test
    void concurrentFirstWritesOfTheSameRouteSettleOnOneRow() throws Exception {
        project("order-service", service.baseUrl());
        long before = configVersion();

        List<JsonHttp.Response> responses = new java.util.ArrayList<>();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            List<java.util.concurrent.Future<JsonHttp.Response>> calls = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                calls.add(executor.submit(() -> new JsonHttp("http://localhost:" + port)
                        .put("/admin/v1/routes/order-service/add_order_note", route(100, 3, null))));
            }
            for (java.util.concurrent.Future<JsonHttp.Response> call : calls) {
                responses.add(call.get());
            }
        }

        assertThat(responses).allSatisfy(response -> assertThat(response.status()).as(response.text()).isEqualTo(200));
        assertThat(responses).filteredOn(response -> !response.json().get("alreadyApplied").booleanValue()).hasSize(1);
        assertThat(configVersion()).isEqualTo(before + 1);
    }

    @Test
    void theSchemaUsesNoReservedWords() {
        assertThat(RuntimeContainers.ORACLE.reservedNames()).isEmpty();
    }

    @Test
    void anOlderPromotionCannotOverwriteANewerOne() {
        project("order-service", service.baseUrl());
        http.put("/admin/v1/routes/order-service/add_order_note", route(120, 4, null));
        long version = configVersion();

        JsonHttp.Response late = http.put("/admin/v1/routes/order-service/add_order_note", route(110, 3, null));

        assertThat(late.status()).isEqualTo(409);
        assertThat(late.json().get("code").stringValue()).isEqualTo("STALE_PROMOTION");
        assertThat(late.json().get("currentPromotionId").longValue()).isEqualTo(120);
        assertThat(configVersion()).isEqualTo(version);
        assertThat(jdbc.sql("SELECT version FROM runtime_route").query(Integer.class).single())
                .isEqualTo(4);
    }

    @Test
    void aRouteNeedsItsProjectAndProfileInTheEnvironment() {
        JsonHttp.Response noProject = http.put("/admin/v1/routes/order-service/add_order_note", route(1, 1, null));
        assertThat(noProject.status()).isEqualTo(409);
        assertThat(noProject.json().get("code").stringValue()).isEqualTo("PROJECT_NOT_FOUND");

        project("order-service", service.baseUrl());
        JsonHttp.Response noProfile = http.put("/admin/v1/routes/order-service/add_order_note",
                route(2, 1, "log-project"));
        assertThat(noProfile.status()).isEqualTo(409);
        assertThat(noProfile.json().get("code").stringValue()).isEqualTo("PROFILE_NOT_FOUND");

        JsonHttp.Response profile = http.put("/admin/v1/response-profiles/log-project", profile(3, 2));
        assertThat(profile.status()).as(profile.text()).isEqualTo(200);
        JsonHttp.Response route = http.put("/admin/v1/routes/order-service/add_order_note", route(4, 1, "log-project"));
        assertThat(route.status()).as(route.text()).isEqualTo(200);
    }

    @Test
    void definitionsAreValidatedWithFormPaths() {
        project("order-service", service.baseUrl());
        String body = """
                {"promotionId": 5, "definitionId": 17, "version": 1, "httpMethod": "GET",
                 "pathTemplate": "/orders/{orderId}", "permissions": [], "anonymous": false,
                 "definition": {"request": {"body": {"mode": "TEMPLATE", "template": {}}}, "timeoutMs": 10},
                 "actor": "sec.zeynep"}
                """;

        JsonHttp.Response response = http.put("/admin/v1/routes/order-service/get_order", body);

        assertThat(response.status()).isEqualTo(400);
        List<String> fields = Json.fieldNames(response.json()).contains("errors")
                ? response.json().get("errors").valueStream().map(error -> error.get("field").stringValue()).toList()
                : List.of();
        assertThat(fields).contains("permissions", "definition.timeoutMs");
    }

    @Test
    void profilesAreValidated() {
        JsonHttp.Response response = http.put("/admin/v1/response-profiles/log-project", """
                {"promotionId": 5, "profileId": 3, "version": 1, "actor": "sec.zeynep",
                 "definition": {"success": {"httpStatus": 200, "body": {"x": "${request.id}"}}}}
                """);
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().get("errors").valueStream().map(error -> error.get("field").stringValue()))
                .contains("definition.success.body.x", "definition.error");
    }

    @Test
    void killSwitchDisablesTheRoute() {
        project("order-service", service.baseUrl());
        http.put("/admin/v1/routes/order-service/add_order_note", route(100, 3, null));
        long before = configVersion();

        JsonHttp.Response disabled = http.post("/admin/v1/routes/order-service/add_order_note/disable",
                "{\"promotionId\": 101, \"actor\": \"sec.zeynep\"}");

        assertThat(disabled.status()).as(disabled.text()).isEqualTo(200);
        assertThat(disabled.json().get("configVersion").longValue()).isEqualTo(before + 1);
        assertThat(jdbc.sql("SELECT status FROM runtime_route").query(String.class).single())
                .isEqualTo("DISABLED");

        JsonHttp.Response replay = http.post("/admin/v1/routes/order-service/add_order_note/disable",
                "{\"promotionId\": 101, \"actor\": \"sec.zeynep\"}");
        assertThat(replay.json().get("alreadyApplied").booleanValue()).isTrue();

        JsonHttp.Response missing = http.post("/admin/v1/routes/order-service/nope/disable",
                "{\"promotionId\": 102, \"actor\": \"sec.zeynep\"}");
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.json().get("code").stringValue()).isEqualTo("ROUTE_NOT_FOUND");

        JsonHttp.Response enabled = http.put("/admin/v1/routes/order-service/add_order_note", route(103, 3, null));
        assertThat(enabled.status()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT status FROM runtime_route").query(String.class).single())
                .isEqualTo("ACTIVE");
    }

    @Test
    void listingShowsProjectsProfilesAndRoutes() {
        project("order-service", service.baseUrl());
        http.put("/admin/v1/response-profiles/log-project", profile(3, 2));
        http.put("/admin/v1/routes/order-service/add_order_note", route(4, 3, "log-project"));

        JsonNode listing = http.get("/admin/v1/routes").json();

        assertThat(listing.get("environment").stringValue()).isEqualTo("PREPROD");
        assertThat(listing.get("configVersion").longValue()).isEqualTo(configVersion());
        assertThat(listing.get("projects").get(0).get("baseUrl").stringValue()).isEqualTo(service.baseUrl());
        assertThat(listing.get("profiles").get(0).get("version").intValue()).isEqualTo(2);
        JsonNode route = listing.get("routes").get(0);
        assertThat(route.get("subtask").stringValue()).isEqualTo("add_order_note");
        assertThat(route.get("version").intValue()).isEqualTo(3);
        assertThat(route.get("responseProfile").stringValue()).isEqualTo("log-project");
        assertThat(route.get("permissions").get(0).stringValue()).isEqualTo("ORDER_WRITE");
        assertThat(route.get("status").stringValue()).isEqualTo("ACTIVE");
    }

    @Test
    void cacheStatusReportsWhatEachInstanceLoaded() {
        project("order-service", service.baseUrl());
        long version = configVersion();
        redis.opsForValue().set(RuntimeSignals.LOADED_KEY_PREFIX + "yapi-a",
                new RuntimeSignals.Loaded(version, Instant.parse("2026-10-13T12:00:00Z")).encode());
        redis.opsForValue().set(RuntimeSignals.LOADED_KEY_PREFIX + "yapi-b", Long.toString(version - 1));

        JsonNode status = http.get("/admin/v1/cache-status").json();

        assertThat(status.get("configVersion").longValue()).isEqualTo(version);
        JsonNode instances = status.get("instances");
        assertThat(instances.size()).isEqualTo(2);
        assertThat(instances.get(0).get("instanceId").stringValue()).isEqualTo("yapi-a");
        assertThat(instances.get(0).get("upToDate").booleanValue()).isTrue();
        assertThat(instances.get(0).get("loadedAt").stringValue()).isEqualTo("2026-10-13T12:00:00Z");
        assertThat(instances.get(1).get("loadedVersion").longValue()).isEqualTo(version - 1);
        assertThat(instances.get(1).get("upToDate").booleanValue()).isFalse();
    }

    @Test
    void probeReadsTheAllowHeader() {
        project("order-service", service.baseUrl());
        service.on("OPTIONS", "/orders/981/notes", (exchange, request) ->
                FakeHttpServer.respond(exchange, 200, Map.of("Allow", "POST,OPTIONS"), null));
        service.on("OPTIONS", "/orders/1", (exchange, request) ->
                FakeHttpServer.respond(exchange, 200, Map.of("Allow", "GET, HEAD, DELETE, OPTIONS"), null));
        service.on("OPTIONS", "/secure/1", (exchange, request) -> FakeHttpServer.respond(exchange, 401, Map.of(), null));
        service.on("OPTIONS", "/moved", (exchange, request) ->
                FakeHttpServer.respond(exchange, 302, Map.of("Location", service.baseUrl() + "/elsewhere"), null));

        JsonNode ok = probe("POST", "/orders/{id}/notes", "{\"id\": \"981\"}");
        assertThat(ok.get("result").stringValue()).isEqualTo("OK");
        assertThat(ok.get("allow").valueStream().map(JsonNode::stringValue)).contains("POST", "OPTIONS");

        assertThat(probe("PUT", "/orders/{id}", "{}").get("result").stringValue()).isEqualTo("WRONG_METHOD");
        assertThat(probe("GET", "/missing/{id}", "{}").get("result").stringValue()).isEqualTo("PATH_NOT_FOUND");
        JsonNode secured = probe("GET", "/secure/{id}", "{}");
        assertThat(secured.get("result").stringValue()).isEqualTo("INCONCLUSIVE");
        assertThat(secured.get("status").intValue()).isEqualTo(401);

        assertThat(probe("GET", "/moved", "{}").get("result").stringValue()).isEqualTo("INCONCLUSIVE");
        assertThat(service.requests("/elsewhere")).isEmpty();
    }

    @Test
    void probeOfAnUnreachableServiceIsInconclusive() {
        project("order-service", "http://127.0.0.1:1");
        JsonNode result = probe("GET", "/orders/{id}", "{}");
        assertThat(result.get("result").stringValue()).isEqualTo("INCONCLUSIVE");
        assertThat(result.get("status").isNull()).isTrue();
    }

    private JsonNode probe(String method, String path, String variables) {
        JsonHttp.Response response = http.post("/admin/v1/probe", """
                {"maintask": "order-service", "httpMethod": "%s", "pathTemplate": "%s", "pathVariables": %s,
                 "actor": "devops.ali"}
                """.formatted(method, path, variables));
        assertThat(response.status()).as(response.text()).isEqualTo(200);
        return response.json();
    }

    @Test
    void specComesFromTheRegisteredBaseUrl() {
        project("order-service", service.baseUrl());
        service.json("GET", "/v3/api-docs", 200, Json.write(CoreFixtures.sampleSpec()));
        service.json("GET", "/internal-docs", 200, "{\"openapi\": \"3.0.1\", \"paths\": {}}");

        JsonHttp.Response spec = http.get("/admin/v1/projects/order-service/spec");
        assertThat(spec.status()).isEqualTo(200);
        assertThat(spec.json()).isEqualTo(CoreFixtures.sampleSpec());
        assertThat(http.get("/admin/v1/projects/order-service/spec?path=/internal-docs").status()).isEqualTo(200);

        for (String path : List.of("/../admin", "/a/%2e%2e/b", "//evil.example/x", "http://evil.example/x")) {
            JsonHttp.Response rejected = http.get("/admin/v1/projects/order-service/spec?path="
                    + java.net.URLEncoder.encode(path, StandardCharsets.UTF_8));
            assertThat(rejected.status()).as(path).isEqualTo(400);
        }

        JsonHttp.Response closed = http.get("/admin/v1/projects/order-service/spec?path=/closed");
        assertThat(closed.status()).isEqualTo(502);
        assertThat(closed.json().get("code").stringValue()).isEqualTo("UPSTREAM_ERROR");
        assertThat(closed.json().get("upstreamStatus").intValue()).isEqualTo(404);

        assertThat(http.get("/admin/v1/projects/unknown/spec").json().get("code").stringValue())
                .isEqualTo("PROJECT_NOT_FOUND");
    }

    @Test
    void testCallsGoToTheBrokerWithTheRegisteredBaseUrl() {
        project("order-service", "http://order-service.preprod.svc:8080");
        service.json("POST", TestCallRequest.PATH, 200, """
                {"request": null, "response": null, "clientResponse": null, "outcome": "INVALID_REQUEST",
                 "interpretation": "x", "errors": [{"field": "siparisNo", "message": "Zorunlu alan eksik"}]}
                """);

        JsonHttp.Response response = http.post("/admin/v1/test", """
                {"maintask": "order-service", "baseUrl": "http://evil.example", "httpMethod": "POST",
                 "pathTemplate": "/orders/{id}/notes", "definition": %s, "sample": {"notMetni": "x"},
                 "userId": "devops.ali", "actor": "devops.ali"}
                """.formatted(CoreFixtures.ADD_ORDER_NOTE));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().get("outcome").stringValue()).isEqualTo("INVALID_REQUEST");
        FakeHttpServer.Recorded forwarded = service.requests(TestCallRequest.PATH).getFirst();
        assertThat(forwarded.header(TestCallRequest.SECRET_HEADER)).isEqualTo("s3cret");
        JsonNode body = Json.parse(forwarded.body());
        assertThat(body.get("baseUrl").stringValue()).isEqualTo("http://order-service.preprod.svc:8080");
        assertThat(body.get("sample").get("notMetni").stringValue()).isEqualTo("x");
    }

    @Test
    void errorsUseTheProblemFormat() {
        JsonHttp.Response response = http.put("/admin/v1/routes/order-service/x", "{not json");
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().get("code").stringValue()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.json().get("type").stringValue()).isEqualTo("about:blank");

        assertThat(http.get("/admin/v1/nothing").json().get("code").stringValue()).isEqualTo("NOT_FOUND");
    }

    @Test
    void theCentralAdminChecksASessionWithTheBrokersRule() {
        redis.opsForValue().set("user:session:12345", SessionRecordCodec.DEFAULT.write(new SessionRecord("12345",
                "tok-1", "10.1.1.1", "Mozilla/5.0", List.of("YAPI_DEVELOPER", "ORDER_READ"))));
        String request = "{\"uid\": \"%s\", \"token\": \"%s\", \"clientIp\": \"%s\", \"userAgent\": \"%s\"}";

        JsonHttp.Response valid = http.post("/admin/v1/sessions/verify",
                request.formatted("12345", "tok-1", "10.1.1.1", "Mozilla/5.0"));
        assertThat(valid.status()).isEqualTo(200);
        assertThat(valid.json()).isEqualTo(Json.parse("{\"valid\": true, \"permissions\": [\"YAPI_DEVELOPER\", \"ORDER_READ\"]}"));

        for (String[] wrong : new String[][]{{"12345", "tok-2", "10.1.1.1", "Mozilla/5.0"},
                {"12345", "tok-1", "10.1.1.2", "Mozilla/5.0"}, {"12345", "tok-1", "10.1.1.1", "curl"},
                {"12345", "TOK-1", "10.1.1.1", "Mozilla/5.0"}, {"67890", "tok-1", "10.1.1.1", "Mozilla/5.0"},
                {"", "tok-1", "10.1.1.1", "Mozilla/5.0"}}) {
            assertThat(http.post("/admin/v1/sessions/verify", request.formatted((Object[]) wrong)).json())
                    .as(String.join(" ", wrong))
                    .isEqualTo(Json.parse("{\"valid\": false, \"permissions\": []}"));
        }
        redis.delete("user:session:12345");
    }
}
