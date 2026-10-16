package com.acme.broker.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.acme.broker.core.json.Json;
import com.acme.broker.core.logging.LogEvent;
import com.acme.broker.core.runtime.RuntimeSignals;
import com.acme.broker.core.session.SessionRecord;
import com.acme.broker.core.session.SessionRecordCodec;
import com.acme.broker.core.testcall.TestCallRequest;
import com.acme.broker.core.testing.CoreFixtures;
import com.acme.broker.core.testing.FakeHttpServer;
import com.acme.broker.core.testing.JsonHttp;
import com.acme.broker.runtime.route.RouteCache;
import com.acme.broker.runtime.route.RouteRepository;
import com.acme.broker.runtime.support.EventCollector;
import com.acme.broker.runtime.support.RuntimeFixture;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.JsonNode;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "broker.environment=TEST",
        "broker.instance-id=broker-test-1",
        "broker.test.enabled=true",
        "broker.test.secret=s3cret",
        "broker.forward.user-context-secret=k3y",
        "broker.applications.mode=static",
        "broker.applications.list=web,Mobil"
})
@EmbeddedKafka(partitions = 1, topics = "broker.logs.v1", bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        brokerProperties = "auto.create.topics.enable=false")
class BrokerFlowTest {

    static final FakeHttpServer backend = FakeHttpServer.start();
    static final Path spool = temporaryDirectory();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        RuntimeFixture.register(registry);
        registry.add("broker.spool.directory", spool::toString);
    }

    @AfterAll
    static void stop() {
        backend.close();
    }

    @LocalServerPort
    int port;

    @Autowired
    RouteCache cache;

    @Autowired
    RouteRepository routes;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    EmbeddedKafkaBroker kafka;

    @Autowired
    MeterRegistry meters;

    JsonHttp http;
    static EventCollector events;

    @BeforeEach
    void setUp() {
        http = new JsonHttp("http://localhost:" + port);
        if (events == null) {
            events = new EventCollector(kafka.getBrokersAsString(), "broker.logs.v1");
        }
        backend.reset();
        RuntimeFixture.reset();
        RuntimeFixture.project("order-service", backend.baseUrl());
        RuntimeFixture.project("LogProject", backend.baseUrl());
        RuntimeFixture.project("files", backend.baseUrl());
        RuntimeFixture.project("down-service", "http://127.0.0.1:1");
        RuntimeFixture.profile("log-project", 2, CoreFixtures.LOG_PROJECT_PROFILE);
        RuntimeFixture.route("order-service", "add_order_note", "POST", "/orders/{id}/notes", List.of("ORDER_WRITE"),
                false, null, CoreFixtures.ADD_ORDER_NOTE);
        RuntimeFixture.route("order-service", "login", "POST", "/auth/login", List.of(), true, null, """
                {"request": {"fields": [{"name": "username", "type": "string", "required": true},
                                        {"name": "password", "type": "string", "required": true}],
                             "body": {"mode": "PASS_THROUGH"}}}
                """);
        RuntimeFixture.route("order-service", "get_order", "GET", "/orders/{id}", List.of("ORDER_READ"), false, null, """
                {"request": {"fields": [{"name": "orderId", "type": "integer", "required": true}],
                             "pathVariables": {"id": "${request.orderId}"}},
                 "retry": {"maxAttempts": 2}}
                """);
        RuntimeFixture.route("order-service", "slow", "POST", "/slow", List.of("ORDER_WRITE"), false, null,
                "{\"timeoutMs\": 200}");
        RuntimeFixture.route("LogProject", "get_id", "GET", "/logs/{id}", List.of("LOGPROJECT_TEMEL"), false,
                "log-project", """
                {"request": {"fields": [{"name": "id", "type": "integer", "required": true}],
                             "pathVariables": {"id": "${request.id}"}},
                 "timeoutMs": 300,
                 "logging": {"logResponseBody": true, "maskFields": ["secret"]}}
                """);
        RuntimeFixture.route("files", "download_report", "GET", "/reports/{id}/excel", List.of("FILES_READ"), false,
                null, """
                {"request": {"fields": [{"name": "id", "type": "integer", "required": true}],
                             "pathVariables": {"id": "${request.id}"}},
                 "response": {"type": "BINARY"},
                 "logging": {"binaryInfo": "Excel raporu"}}
                """);
        RuntimeFixture.route("down-service", "ping", "GET", "/ping", List.of(), true, null, "{}");
        RuntimeFixture.project("giris", backend.baseUrl());
        RuntimeFixture.route("giris", "login", "POST", "/login", List.of(), true, null, LOGIN);
        RuntimeFixture.route("giris", "logout", "POST", "/logout", List.of(), true, null, LOGOUT);
        RuntimeFixture.project("giris_public", backend.baseUrl());
        RuntimeFixture.route("giris_public", "duyurular", "GET", "/announcements", List.of(), true, null, "{}");
        cache.reload();
        UIDS.clear();
        session("tok-1", "u12345", "ORDER_WRITE", "ORDER_READ", "LOGPROJECT_TEMEL", "FILES_READ");
        session("tok-2", "u777", "OTHER_PERMISSION");
    }

    static final String CLIENT_IP = "10.20.30.40";
    static final String USER_AGENT = "agent-hash";
    /** The login service's routes, as its README defines them. */
    static final String LOGIN = """
            {"request": {"fields": [{"name": "password", "type": "string", "required": true}],
                         "headers": {"X-Clientip": "${header.X-Clientip}", "X-User-Agent": "${header.X-User-Agent}"},
                         "body": {"mode": "TEMPLATE", "template": {"uid": "${request.uid}", "password": "${request.password}"}}}}
            """;
    static final String LOGOUT = """
            {"request": {"headers": {"X-Clientip": "${header.X-Clientip}", "X-User-Agent": "${header.X-User-Agent}"},
                         "body": {"mode": "TEMPLATE", "template": {"uid": "${request.uid}"}}}}
            """;
    /** Token → uid of the sessions a test opened; requests with the token carry the uid like frontends do. */
    static final Map<String, String> UIDS = new java.util.HashMap<>();

    /** Writes the record the login service writes ({@code user:session:{uid}}), for this test's client. */
    private void session(String token, String uid, String... permissions) {
        UIDS.put(token, uid);
        redis.opsForValue().set(SessionRecordCodec.DEFAULT.key(uid), SessionRecordCodec.DEFAULT.write(
                new SessionRecord(uid, token, CLIENT_IP, USER_AGENT, List.of(permissions))));
    }

    private JsonHttp.Response broker(String token, String body) {
        return broker(token, body, Map.of());
    }

    /**
     * A frontend's request: the session's uid and the application {@code web} are added to a JSON body that lacks
     * them, and the client headers of the login unless {@code extra} gives others (a null value leaves one out).
     */
    private JsonHttp.Response broker(String token, String body, Map<String, String> extra) {
        Map<String, String> headers = new java.util.HashMap<>();
        headers.put("X-Clientip", CLIENT_IP);
        headers.put("X-User-Agent", USER_AGENT);
        headers.putAll(extra);
        headers.values().removeIf(java.util.Objects::isNull);
        if (token != null) {
            headers.put("X-Token", token);
        }
        JsonNode parsed = Json.tryParse(body);
        if (parsed instanceof tools.jackson.databind.node.ObjectNode object) {
            if (!object.has("uid") && UIDS.containsKey(token)) {
                object.put("uid", UIDS.get(token));
            }
            if (!object.has("application")) {
                object.put("application", "web");
            }
            body = Json.write(object);
        }
        return http.send("POST", "/broker", body, headers);
    }

    @Test
    void responseDataIsLoggedOnlyWhenTheRouteAsksForIt() {
        RuntimeFixture.route("order-service", "find_customer", "GET", "/customers/{id}", List.of("ORDER_READ"), false,
                null, """
                {"request": {"fields": [{"name": "id", "type": "integer", "required": true}],
                             "pathVariables": {"id": "${request.id}"}},
                 "logging": {"logResponseBody": true, "maskFields": ["tckn"], "maxBodyBytes": 64}}
                """);
        cache.reload();
        backend.json("GET", "/customers/1", 200, "{\"ok\": true, \"data\": {\"tckn\": \"12345678901\", \"ad\": \"Ayşe\"}}");
        backend.json("GET", "/customers/2", 200,
                "{\"ok\": true, \"data\": {\"ad\": \"" + "Çağrı ".repeat(20) + "\"}}");

        JsonHttp.Response small = broker("tok-1", "{\"maintask\": \"order-service\", \"subtask\": \"find_customer\", \"id\": 1}");
        JsonHttp.Response large = broker("tok-1", "{\"maintask\": \"order-service\", \"subtask\": \"find_customer\", \"id\": 2}");

        assertThat(small.json().get("data").get("tckn").stringValue()).isEqualTo("12345678901");
        LogEvent logged = events.awaitType(small.header("X-Request-Id"), LogEvent.EventType.RESPONSE);
        assertThat(logged.clientResponse()).isEqualTo(Json.parse("""
                {"ok": true, "data": "{\\"tckn\\":\\"xxxx\\",\\"ad\\":\\"Ayşe\\"}"}
                """));
        assertThat(logged.bodyTruncated()).isFalse();
        LogEvent cut = events.awaitType(large.header("X-Request-Id"), LogEvent.EventType.RESPONSE);
        assertThat(Json.fieldNames(cut.clientResponse())).containsExactly("status", "data");
        assertThat(Json.utf8Length(Json.text(cut.clientResponse().get("data")))).isLessThanOrEqualTo(64);
        assertThat(cut.bodyTruncated()).isTrue();
    }

    @Test
    void definitionsLargerThanAVarcharLoadAndForward() {
        String definition = CoreFixtures.largeAddOrderNote(40_000);
        RuntimeFixture.route("order-service", "add_long_note", "POST", "/orders/{id}/notes", List.of("ORDER_WRITE"),
                false, null, definition);
        cache.reload();
        backend.json("POST", "/orders/981/notes", 201, "{\"noteId\": 56}");

        JsonHttp.Response response = broker("tok-1", """
                {"maintask": "order-service", "subtask": "add_long_note", "siparisNo": 981, "notMetni": "Uzun"}
                """);

        assertThat(response.status()).as(response.text()).isEqualTo(201);
        JsonNode sent = Json.parse(backend.requests("/orders/981/notes").getFirst().body());
        assertThat(sent.get("terms")).isEqualTo(Json.parse(definition).at("/request/body/template/terms"));
    }

    @Test
    void aSnapshotReadsOneStateOfTheDatabase() {
        long[] seen = routes.inSnapshot(() -> {
            long before = routes.currentVersion();
            long written = RuntimeFixture.project("order-service", "http://moved.example:8080");
            return new long[] {before, written, routes.currentVersion()};
        });

        assertThat(seen[1]).isGreaterThan(seen[0]);
        assertThat(seen[2]).isEqualTo(seen[0]);
        assertThat(routes.currentVersion()).isEqualTo(seen[1]);
    }

    @Test
    void forwardsWithTheDefinitionAndLogsBothEvents() {
        backend.json("POST", "/orders/981/notes", 201, "{\"noteId\": 55}");
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";

        JsonHttp.Response response = broker("tok-1", """
                {"maintask": "order-service", "subtask": "add_order_note", "application": "web", "uid": "u12345",
                 "siparisNo": 981, "notMetni": "Kapıya bırakın", "password": "gizli"}
                """, Map.of("X-Clientip", "10.20.30.40", "X-User-Agent", "agent-hash", "Accept-Language", "tr-TR",
                "traceparent", "00-" + traceId + "-00f067aa0ba902b7-01"));

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.json()).isEqualTo(Json.parse("{\"noteId\": 55}"));
        String requestId = response.header("X-Request-Id");
        assertThat(UUID.fromString(requestId).version()).isEqualTo(7);

        FakeHttpServer.Recorded sent = backend.requests("/orders/981/notes").getFirst();
        assertThat(sent.query()).isEqualTo("notify=false");
        assertThat(sent.header("X-Channel")).isEqualTo("WEB");
        assertThat(sent.header("X-uid")).isEqualTo("u12345");
        assertThat(sent.header("Accept-Language")).isEqualTo("tr-TR");
        assertThat(sent.header("X-Request-Id")).isEqualTo(requestId);
        assertThat(sent.header("traceparent")).startsWith("00-" + traceId + "-").doesNotContain("00f067aa0ba902b7");
        assertThat(sent.header("X-User-Context")).startsWith("v1.");
        assertThat(Json.parse(sent.body())).isEqualTo(Json.parse("""
                {"text": "Kapıya bırakın", "authorId": "u12345", "source": "WEB"}
                """));

        LogEvent request = events.awaitType(requestId, LogEvent.EventType.REQUEST);
        assertThat(request.traceId()).isEqualTo(traceId);
        assertThat(request.environment()).isEqualTo("TEST");
        assertThat(request.maintask()).isEqualTo("order-service");
        assertThat(request.routeVersion()).isEqualTo(3);
        assertThat(request.uid()).isEqualTo("u12345");
        assertThat(request.userId()).isEqualTo("u12345");
        assertThat(request.permList()).containsExactly("ORDER_WRITE", "ORDER_READ", "LOGPROJECT_TEMEL", "FILES_READ");
        assertThat(request.token()).isEqualTo("tok-1");
        assertThat(request.clientIp()).isEqualTo("10.20.30.40");
        assertThat(request.userAgent()).isEqualTo("agent-hash");
        assertThat(request.queryId()).isNotNull();
        assertThat(Json.text(request.body().get("password"))).isEqualTo("xxxx");
        assertThat(request.body().get("siparisNo").intValue()).isEqualTo(981);
        assertThat(request.brokerInstance()).isEqualTo("broker-test-1");

        LogEvent responseEvent = events.awaitType(requestId, LogEvent.EventType.RESPONSE);
        assertThat(responseEvent.statusCode()).isEqualTo(201);
        assertThat(responseEvent.outcome()).isEqualTo("SUCCESS");
        assertThat(responseEvent.targetPath()).isEqualTo("/orders/981/notes");
        assertThat(responseEvent.body()).isNull();
        assertThat(responseEvent.bodySize()).isEqualTo("{\"noteId\": 55}".length());
        assertThat(responseEvent.clientResponse()).isEqualTo(Json.parse("{\"status\": 201}"));
        assertThat(responseEvent.durationMs()).isNotNull();

        assertThat(meters.find("broker.requests").tags("maintask", "order-service", "subtask", "add_order_note",
                "outcome", "SUCCESS").timer().count()).isPositive();
        assertThat(meters.find("broker.gelen.istekler").tags("maintask", "order-service", "subtask",
                "add_order_note").counter().count()).isPositive();
    }

    @Test
    void namesMatchWithoutCaseUnderATurkishLocaleAndProfilesWrapByRealStatus() {
        Locale original = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            backend.json("GET", "/logs/7", 200, "{\"id\": 7, \"secret\": \"s\"}");
            JsonHttp.Response ok = broker("tok-1", "{\"maintask\": \"LOGPROJECT\", \"subtask\": \"GET_ID\", \"id\": 7}");
            assertThat(ok.status()).isEqualTo(200);
            assertThat(ok.json()).isEqualTo(Json.parse("""
                    {"BACKEND_STATUS": 200, "status": 200, "data": {"id": 7, "secret": "s"}}
                    """));
            LogEvent response = events.awaitType(ok.header("X-Request-Id"), LogEvent.EventType.RESPONSE);
            assertThat(response.maintask()).isEqualTo("LogProject");
            assertThat(response.subtask()).isEqualTo("get_id");
            assertThat(response.responseProfile()).isEqualTo("log-project");
            assertThat(response.responseProfileVersion()).isEqualTo(2);
            assertThat(response.body()).isEqualTo(Json.parse("{\"id\": 7, \"secret\": \"xxxx\"}"));
            // the route logs response data: data is the masked JSON text, still a string for Elastic
            assertThat(response.clientResponse()).isEqualTo(Json.parse("""
                    {"BACKEND_STATUS": 200, "status": 200, "data": "{\\"id\\":7,\\"secret\\":\\"xxxx\\"}"}
                    """));
            assertThat(response.bodyTruncated()).isFalse();
            LogEvent request = events.awaitType(ok.header("X-Request-Id"), LogEvent.EventType.REQUEST);
            assertThat(Json.text(request.body().get("maintask"))).isEqualTo("LOGPROJECT");

            backend.json("GET", "/logs/8", 404, "{\"message\": \"kayıt yok\"}");
            JsonHttp.Response notFound = broker("tok-1", "{\"maintask\": \"logproject\", \"subtask\": \"get_id\", \"id\": 8}");
            assertThat(notFound.status()).isEqualTo(400);
            assertThat(notFound.json()).isEqualTo(Json.parse("{\"status\": 400, \"reason\": \"LogProject Hata!\"}"));
            LogEvent business = events.awaitType(notFound.header("X-Request-Id"), LogEvent.EventType.RESPONSE);
            assertThat(business.statusCode()).isEqualTo(404);
            assertThat(business.outcome()).isEqualTo("BUSINESS_ERROR");

            backend.json("GET", "/logs/9", 500, "{\"trace\": \"java.lang.NullPointerException\"}");
            JsonHttp.Response failed = broker("tok-1", "{\"maintask\": \"LogProject\", \"subtask\": \"get_id\", \"id\": 9}");
            assertThat(failed.status()).isEqualTo(400);
            assertThat(failed.text()).doesNotContain("NullPointerException");
            assertThat(events.awaitType(failed.header("X-Request-Id"), LogEvent.EventType.RESPONSE).outcome())
                    .isEqualTo("TECHNICAL_ERROR");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void brokerErrorsUseTheCommonFormatAndAreLoggedToo() {
        JsonHttp.Response noToken = broker(null, "{\"maintask\": \"order-service\", \"subtask\": \"add_order_note\"}");
        assertError(noToken, 400, "INVALID_REQUEST");

        assertError(broker("tok-1", "not json"), 400, "INVALID_REQUEST");

        JsonHttp.Response unknown = broker("tok-1", "{\"maintask\": \"order-service\", \"subtask\": \"nope\"}");
        assertError(unknown, 404, "UNKNOWN_TASK");
        assertThat(meters.find("broker.requests").tags("maintask", "order-service", "subtask", "UNKNOWN",
                "outcome", "UNKNOWN_TASK").timer().count()).isPositive();
        assertThat(meters.find("broker.gelen.istekler").tags("maintask", "order-service", "subtask",
                "Bilinmeyen Endpoint").counter().count()).isPositive();
        assertThat(meters.find("broker.gelen.istekler").tags("maintask", "Bilinmeyen Proje", "subtask",
                "Bilinmeyen Endpoint").counter()).isNull();
        broker("tok-1", "{\"maintask\": \"ghost\", \"subtask\": \"nope\"}");
        assertThat(meters.find("broker.gelen.istekler").tags("maintask", "Bilinmeyen Proje", "subtask",
                "Bilinmeyen Endpoint").counter().count()).isPositive();

        String body = "{\"maintask\": \"order-service\", \"subtask\": \"add_order_note\", \"siparisNo\": 1, \"notMetni\": \"x\"}";
        assertError(broker("tok-2", body), 403, "DENIED");

        JsonHttp.Response invalid = broker("tok-1", """
                {"maintask": "order-service", "subtask": "add_order_note", "siparisNo": "abc"}
                """);
        assertError(invalid, 400, "INVALID_REQUEST");
        assertThat(invalid.json().get("errors").valueStream().map(error -> error.get("field").stringValue()))
                .containsExactlyInAnyOrder("siparisNo", "notMetni");
        assertThat(backend.requests()).isEmpty();
    }

    private void assertError(JsonHttp.Response response, int status, String outcome) {
        assertThat(response.status()).isEqualTo(status);
        JsonNode body = response.json();
        assertThat(body.get("result").booleanValue()).isFalse();
        assertThat(body.get("status").intValue()).isEqualTo(status);
        assertThat(body.get("outcome").stringValue()).isEqualTo(outcome);
        assertThat(body.get("message").stringValue()).isNotBlank();
        assertThat(body.get("requestId").stringValue()).isEqualTo(response.header("X-Request-Id"));
        List<LogEvent> logged = events.await(response.header("X-Request-Id"), 2);
        assertThat(logged).extracting(LogEvent::eventType)
                .containsExactly(LogEvent.EventType.REQUEST, LogEvent.EventType.RESPONSE);
        assertThat(logged.get(1).outcome()).isEqualTo(outcome);
        assertThat(logged.get(1).statusCode()).isEqualTo(status);
    }

    @Test
    void loginAndLogoutGoToTheLoginServiceWhateverTheBodyNames() {
        backend.json("POST", "/login", 200, """
                {"response": true, "status": 200, "LDAP": true, "login": true, "X-Token": "9b1d2c3e-new"}
                """);
        backend.json("POST", "/logout", 200, "{\"response\": true, \"status\": 200, \"message\": \"logged out\"}");
        double counted = legacyCount("order-service", "login");

        JsonHttp.Response login = broker("login", """
                {"maintask": "order-service", "subtask": "login", "uid": "u42", "password": "p4ss"}
                """);

        assertThat(login.status()).isEqualTo(200);
        assertThat(login.json().get("X-Token").stringValue()).isEqualTo("9b1d2c3e-new");
        FakeHttpServer.Recorded sent = backend.requests("/login").getFirst();
        assertThat(Json.parse(sent.body())).isEqualTo(Json.parse("{\"uid\": \"u42\", \"password\": \"p4ss\"}"));
        assertThat(sent.header("X-Clientip")).isEqualTo(CLIENT_IP);
        assertThat(sent.header("X-User-Agent")).isEqualTo(USER_AGENT);
        assertThat(sent.header("X-uid")).isNull();
        assertThat(sent.header("X-User-Context")).startsWith("v1.");
        JsonNode context = Json.parse(java.util.Base64.getUrlDecoder().decode(
                sent.header("X-User-Context").split("\\.")[1]));
        assertThat(context.get("userId").isNull()).isTrue();
        LogEvent request = events.awaitType(login.header("X-Request-Id"), LogEvent.EventType.REQUEST);
        assertThat(request.sessionOperation()).isEqualTo("login");
        assertThat(request.maintask()).isEqualTo("order-service");
        assertThat(request.subtask()).isEqualTo("login");
        assertThat(request.userId()).isNull();
        assertThat(Json.text(request.body().get("password"))).isEqualTo("xxxx");
        LogEvent response = events.awaitType(login.header("X-Request-Id"), LogEvent.EventType.RESPONSE);
        assertThat(response.clientResponse()).isEqualTo(Json.parse("""
                {"response": true, "status": 200, "LDAP": true, "login": true, "X-Token": "9b1d2c3e-new", "data": ""}
                """));
        assertThat(response.body()).isNull();
        assertThat(legacyCount("order-service", "login")).isEqualTo(counted);

        JsonHttp.Response logout = broker("logout", "{\"maintask\": \"anything\", \"uid\": \"u42\"}");
        assertThat(logout.json()).isEqualTo(Json.parse("{\"response\": true, \"status\": 200, \"message\": \"logged out\"}"));
        assertThat(Json.parse(backend.requests("/logout").getFirst().body())).isEqualTo(Json.parse("{\"uid\": \"u42\"}"));
        assertThat(events.awaitType(logout.header("X-Request-Id"), LogEvent.EventType.REQUEST).sessionOperation())
                .isEqualTo("logout");
    }

    @Test
    void aSessionOperationWithoutAnAnonymousRouteIsAConfigurationError() {
        RuntimeFixture.route("giris", "logout", "POST", "/logout", List.of("ORDER_WRITE"), false, null, LOGOUT);
        cache.reload();

        assertError(broker("logout", "{\"uid\": \"u42\"}"), 503, "TECHNICAL_ERROR");
        assertThat(backend.requests()).isEmpty();
    }

    @Test
    void publicMaintasksAreOrdinaryRoutesEvenWithALoginToken() {
        backend.json("GET", "/announcements", 200, "{\"items\": []}");

        JsonHttp.Response response = broker("login", "{\"maintask\": \"GIRIS_PUBLIC\", \"subtask\": \"duyurular\"}");

        assertThat(response.status()).isEqualTo(200);
        assertThat(backend.requests("/announcements")).hasSize(1);
        assertThat(backend.requests("/login")).isEmpty();
        assertThat(events.awaitType(response.header("X-Request-Id"), LogEvent.EventType.REQUEST).sessionOperation())
                .isNull();
    }

    @Test
    void anApplicationThatIsNotAllowedGetsTheLegacyAnswer() {
        backend.json("GET", "/orders/1", 200, "{}");
        String body = "{\"maintask\": \"order-service\", \"subtask\": \"get_order\", \"orderId\": 1, \"application\": %s}";

        for (String application : List.of("\"kiosk\"", "null", "\"\"")) {
            JsonHttp.Response refused = broker("tok-1", body.formatted(application));
            assertThat(refused.status()).isEqualTo(200);
            assertThat(refused.text()).isEqualTo("{\"response\":false}");
            LogEvent response = events.awaitType(refused.header("X-Request-Id"), LogEvent.EventType.RESPONSE);
            assertThat(response.outcome()).isEqualTo("DENIED");
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.clientResponse()).isEqualTo(Json.parse("{\"response\": false, \"data\": \"\"}"));
        }
        JsonHttp.Response noApplication = broker("tok-1", "{\"maintask\": \"order-service\", \"subtask\": \"get_order\", \"orderId\": 1}"
                .replace("}", ", \"application\": null}"));
        assertThat(noApplication.text()).isEqualTo("{\"response\":false}");
        assertThat(broker("login", "{\"uid\": \"u42\", \"application\": \"kiosk\"}").text())
                .isEqualTo("{\"response\":false}");
        assertThat(broker("anything", "{\"maintask\": \"down-service\", \"subtask\": \"ping\", \"application\": \"kiosk\"}")
                .text()).isEqualTo("{\"response\":false}");
        assertThat(backend.requests()).isEmpty();

        assertThat(broker("tok-1", body.formatted("\"MOBIL\"")).status()).isEqualTo(200);
        assertThat(backend.requests("/orders/1")).hasSize(1);
    }

    @Test
    void anInvalidSessionGetsTheLegacyAnswerWhetherTheRouteExistsOrNot() {
        String known = "{\"maintask\": \"order-service\", \"subtask\": \"get_order\", \"orderId\": 1}";
        String unknown = "{\"maintask\": \"order-service\", \"subtask\": \"nope\"}";

        for (JsonHttp.Response refused : List.of(broker("missing-session", known), broker("missing-session", unknown))) {
            assertThat(refused.status()).isEqualTo(200);
            JsonNode body = refused.json();
            assertThat(Json.fieldNames(body)).containsExactly("response", "status", "reason");
            assertThat(body.get("response").isBoolean()).isTrue();
            assertThat(body.get("response").booleanValue()).isFalse();
            assertThat(body.get("status").isInt()).isTrue();
            assertThat(body.get("status").intValue()).isEqualTo(301);
            assertThat(body.get("reason").stringValue()).isEqualTo("check X-Token");
            LogEvent response = events.awaitType(refused.header("X-Request-Id"), LogEvent.EventType.RESPONSE);
            assertThat(response.outcome()).isEqualTo("DENIED");
            assertThat(response.clientResponse()).isEqualTo(Json.parse("""
                    {"response": false, "status": 301, "reason": "check X-Token", "data": ""}
                    """));
        }
        assertError(broker("tok-1", unknown), 404, "UNKNOWN_TASK");
        assertThat(backend.requests()).isEmpty();
    }

    @Test
    void theSessionBelongsToTheBodysUidAndTheLoginsClientOnly() {
        backend.json("GET", "/orders/1", 200, "{}");
        String body = "{\"maintask\": \"order-service\", \"subtask\": \"get_order\", \"orderId\": 1}";
        String otherUid = "{\"maintask\": \"order-service\", \"subtask\": \"get_order\", \"orderId\": 1, \"uid\": \"u777\"}";
        String noUid = "{\"maintask\": \"order-service\", \"subtask\": \"get_order\", \"orderId\": 1, \"uid\": null}";

        assertThat(broker("tok-1", body).status()).isEqualTo(200);
        Map<String, Map<String, String>> refused = new java.util.LinkedHashMap<>();
        refused.put("başka client IP", Map.of("X-Clientip", "10.20.30.41"));
        refused.put("başka user agent", Map.of("X-User-Agent", "other-agent"));
        refused.put("büyük harfli user agent", Map.of("X-User-Agent", "AGENT-HASH"));
        refused.put("client IP yok", java.util.Collections.singletonMap("X-Clientip", null));
        refused.put("user agent yok", java.util.Collections.singletonMap("X-User-Agent", null));
        refused.forEach((name, headers) -> assertThat(broker("tok-1", body, headers).json().path("status").intValue())
                .as(name).isEqualTo(301));
        assertThat(broker("TOK-1", body.replace("}", ", \"uid\": \"u12345\"}")).json().path("status").intValue())
                .as("büyük harfli token").isEqualTo(301);
        assertThat(broker("tok-1", otherUid).json().path("status").intValue()).as("başkasının uid'i").isEqualTo(301);
        assertThat(broker("tok-1", noUid).json().path("status").intValue()).as("uid yok").isEqualTo(301);
        assertThat(backend.requests("/orders/1")).hasSize(1);
        assertThat(backend.requests("/orders/1").getFirst().header("X-uid")).isEqualTo("u12345");
    }

    @Test
    void anAnonymousRouteKnowsTheUserOnlyWithAValidSession() {
        backend.json("GET", "/ping", 200, "{}");
        RuntimeFixture.project("down-service", backend.baseUrl());
        cache.reload();

        broker("tok-1", "{\"maintask\": \"down-service\", \"subtask\": \"ping\"}");
        broker("tok-1", "{\"maintask\": \"down-service\", \"subtask\": \"ping\"}", Map.of("X-User-Agent", "other"));

        List<FakeHttpServer.Recorded> pings = backend.requests("/ping");
        assertThat(pings).hasSize(2);
        assertThat(pings.get(0).header("X-uid")).isEqualTo("u12345");
        assertThat(pings.get(1).header("X-uid")).isNull();
    }

    private double legacyCount(String maintask, String subtask) {
        var counter = meters.find("broker.gelen.istekler").tags("maintask", maintask, "subtask", subtask).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void fileResponsesAreStreamedAndTheirBodyIsNotLogged() {
        byte[] excel = new byte[100_000];
        new java.util.Random(7).nextBytes(excel);
        backend.on("GET", "/reports/5/excel", (exchange, request) -> FakeHttpServer.respond(exchange, 200,
                Map.of("Content-Type", "application/vnd.ms-excel",
                        "Content-Disposition", "attachment; filename=\"rapor.xlsx\""), excel));

        JsonHttp.Response response = broker("tok-1", "{\"maintask\": \"files\", \"subtask\": \"download_report\", \"id\": 5}");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.bytes()).isEqualTo(excel);
        assertThat(response.header("Content-Disposition")).isEqualTo("attachment; filename=\"rapor.xlsx\"");
        LogEvent event = events.awaitType(response.header("X-Request-Id"), LogEvent.EventType.RESPONSE);
        assertThat(event.body()).isNull();
        assertThat(event.bodySize()).isEqualTo(excel.length);
        assertThat(event.clientResponse()).isEqualTo(Json.parse("{\"info\": \"Excel raporu\"}"));

        // a JSON route with a profile: Content-Disposition alone makes it a file, the profile is not applied
        backend.on("GET", "/logs/11", (exchange, request) -> FakeHttpServer.respond(exchange, 200,
                Map.of("Content-Type", "application/pdf", "Content-Disposition", "inline; filename=belge.pdf"),
                "%PDF-1.7".getBytes(StandardCharsets.US_ASCII)));
        JsonHttp.Response pdf = broker("tok-1", "{\"maintask\": \"LogProject\", \"subtask\": \"get_id\", \"id\": 11}");
        assertThat(pdf.text()).isEqualTo("%PDF-1.7");
        assertThat(events.awaitType(pdf.header("X-Request-Id"), LogEvent.EventType.RESPONSE).clientResponse())
                .isEqualTo(Json.parse("{\"info\": \"Dosya yanıtı: belge.pdf\"}"));
    }

    @Test
    void timeoutsAndConnectionFailures() {
        backend.on("POST", "/slow", (exchange, request) -> {
            sleep(1_000);
            FakeHttpServer.respond(exchange, 200, Map.of(), "{}".getBytes(StandardCharsets.UTF_8));
        });
        assertError(broker("tok-1", "{\"maintask\": \"order-service\", \"subtask\": \"slow\"}"), 504, "TIMEOUT");

        backend.on("GET", "/logs/12", (exchange, request) -> {
            sleep(1_000);
            FakeHttpServer.respond(exchange, 200, Map.of(), "{}".getBytes(StandardCharsets.UTF_8));
        });
        JsonHttp.Response wrapped = broker("tok-1", "{\"maintask\": \"LogProject\", \"subtask\": \"get_id\", \"id\": 12}");
        assertThat(wrapped.status()).isEqualTo(400);
        assertThat(wrapped.json().get("reason").stringValue()).isEqualTo("LogProject Hata!");
        assertThat(events.awaitType(wrapped.header("X-Request-Id"), LogEvent.EventType.RESPONSE).outcome())
                .isEqualTo("TIMEOUT");

        assertError(broker("anything", "{\"maintask\": \"down-service\", \"subtask\": \"ping\"}"), 502,
                "TECHNICAL_ERROR");
    }

    @Test
    void getIsRetriedWhenTheServiceIsUnavailable() {
        AtomicInteger calls = new AtomicInteger();
        backend.on("GET", "/orders/42", (exchange, request) -> {
            int call = calls.incrementAndGet();
            FakeHttpServer.respond(exchange, call == 1 ? 503 : 200, Map.of("Content-Type", "application/json"),
                    ("{\"call\": " + call + "}").getBytes(StandardCharsets.UTF_8));
        });

        JsonHttp.Response response = broker("tok-1", "{\"maintask\": \"order-service\", \"subtask\": \"get_order\", \"orderId\": 42}");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().get("call").intValue()).isEqualTo(2);
    }

    @Test
    void changesArriveThroughTheRedisSignal() {
        long version = RuntimeFixture.route("order-service", "new_one", "GET", "/new", List.of(), true, null, "{}");
        assertThat(cache.find("order-service", "new_one")).isEmpty();

        redis.convertAndSend(RuntimeSignals.ROUTES_CHANGED_CHANNEL, Long.toString(version));

        await().atMost(Duration.ofSeconds(5)).until(() -> cache.find("order-service", "new_one").isPresent());
        RuntimeSignals.Loaded loaded = RuntimeSignals.Loaded.decode(
                redis.opsForValue().get(RuntimeSignals.LOADED_KEY_PREFIX + "broker-test-1")).orElseThrow();
        assertThat(loaded.version()).isEqualTo(version);
        assertThat(redis.getExpire(RuntimeSignals.LOADED_KEY_PREFIX + "broker-test-1")).isBetween(1L, 60L);
    }

    @Test
    void internalTestEndpointRunsTheRealMappingWithoutLogging() {
        backend.json("POST", "/orders/5/notes", 201, "{\"noteId\": 9}");
        String call = """
                {"maintask": "order-service", "baseUrl": "%s", "httpMethod": "POST",
                 "pathTemplate": "/orders/{id}/notes", "definition": %s,
                 "responseProfile": {"name": "log-project", "version": 2, "definition": %s},
                 "sample": %s, "headers": {"Accept-Language": "en"}, "userId": "devops.ali", "actor": "devops.ali"}
                """;

        JsonHttp.Response forbidden = http.send("POST", TestCallRequest.PATH, call.formatted(backend.baseUrl(),
                CoreFixtures.ADD_ORDER_NOTE, CoreFixtures.LOG_PROJECT_PROFILE, "{}"), Map.of());
        assertThat(forbidden.status()).isEqualTo(403);

        JsonHttp.Response response = http.send("POST", TestCallRequest.PATH, call.formatted(backend.baseUrl(),
                        CoreFixtures.ADD_ORDER_NOTE, CoreFixtures.LOG_PROJECT_PROFILE,
                        "{\"siparisNo\": 5, \"notMetni\": \"x\"}"),
                Map.of(TestCallRequest.SECRET_HEADER, "s3cret"));

        assertThat(response.status()).as(response.text()).isEqualTo(200);
        JsonNode result = response.json();
        assertThat(result.get("request").get("url").stringValue()).isEqualTo(backend.baseUrl() + "/orders/5/notes?notify=false");
        assertThat(result.get("request").get("headers").get("X-uid").stringValue()).isEqualTo("devops.ali");
        assertThat(result.get("request").get("headers").get("Accept-Language").stringValue()).isEqualTo("en");
        assertThat(result.get("request").get("body").get("authorId").stringValue()).isEqualTo("devops.ali");
        assertThat(result.get("response").get("status").intValue()).isEqualTo(201);
        assertThat(result.get("response").get("body")).isEqualTo(Json.parse("{\"noteId\": 9}"));
        assertThat(result.get("clientResponse").get("status").intValue()).isEqualTo(200);
        assertThat(result.get("clientResponse").get("body").get("data").get("noteId").intValue()).isEqualTo(9);
        assertThat(result.get("outcome").stringValue()).isEqualTo("SUCCESS");
        assertThat(result.get("interpretation").stringValue()).isEqualTo("Çalışıyor");

        JsonHttp.Response invalid = http.send("POST", TestCallRequest.PATH, call.formatted(backend.baseUrl(),
                CoreFixtures.ADD_ORDER_NOTE, CoreFixtures.LOG_PROJECT_PROFILE, "{\"siparisNo\": \"x\"}"),
                Map.of(TestCallRequest.SECRET_HEADER, "s3cret"));
        assertThat(invalid.json().get("outcome").stringValue()).isEqualTo("INVALID_REQUEST");
        assertThat(invalid.json().get("request").isNull()).isTrue();
        assertThat(invalid.json().get("errors").valueStream().map(error -> error.get("field").stringValue()))
                .containsExactlyInAnyOrder("siparisNo", "notMetni");
        assertThat(backend.requests("/orders/5/notes")).hasSize(1);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("broker-spool");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
