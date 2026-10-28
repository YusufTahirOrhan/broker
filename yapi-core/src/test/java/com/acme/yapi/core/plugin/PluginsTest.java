package com.acme.yapi.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.request.BackendRequest;
import com.acme.yapi.core.request.BackendRequestBuilder;
import com.acme.yapi.core.request.RequestContext;
import com.acme.yapi.core.request.RequestScope;
import com.acme.yapi.core.route.HttpMethodName;
import com.acme.yapi.core.route.PathTemplate;
import com.acme.yapi.core.route.RouteDefinition;
import com.acme.yapi.core.route.RouteDefinitionParser;
import com.acme.yapi.core.route.RouteValidator;
import com.acme.yapi.core.validation.FieldError;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class PluginsTest {

    private static final Instant NOW = Instant.parse("2026-10-28T09:15:30.123Z");
    private static final Instant RECEIVED = NOW.minusMillis(250);
    private static final RouteValidator.Context READABLE = RouteValidator.Context.of(List.of("X-Clientip", "X-User-Agent"));

    private static Plugin plugin(String name, String definition) {
        PluginDefinition.Parsed parsed = PluginDefinition.parse(Json.parse(definition), READABLE);
        assertThat(parsed.errors()).as(name).isEmpty();
        return new Plugin(name, 3, parsed.definition());
    }

    private static List<String> errors(String definition) {
        return PluginDefinition.parse(Json.parse(definition), READABLE).errors().stream().map(FieldError::field).toList();
    }

    private static RouteDefinition definition(String json) {
        return RouteDefinitionParser.parse(Json.parse(json)).definition();
    }

    private static RequestScope scope() {
        return new RequestScope("PREPROD", "4bf92f3577b34da6a3ce929d0e0e4736", "order-service", "add_note", 4,
                "10.0.0.5", "agent-1", List.of("ORDER_WRITE", "ORDER_READ"), RECEIVED, null,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static RequestContext context(String body, String userId) {
        ObjectNode parsed = (ObjectNode) Json.parse(body);
        return new RequestContext(parsed, parsed, Map.of("X-Clientip", "10.0.0.5")::get, READABLE, userId, null, "req-1")
                .withScope(scope());
    }

    @Test
    void mistakesAreRejectedWithTheirFormPath() {
        assertThat(errors("""
                {"scope": "ALL", "extra": 1, "request": [], "response": []}
                """)).containsExactlyInAnyOrder("extra", "scope", "request");
        assertThat(errors("""
                {"scope": "GLOBAL", "projects": ["order-service"], "request": [{"op": "remove", "target": "header", "name": "X-A"}]}
                """)).containsExactly("projects");
        assertThat(errors("""
                {"scope": "PROJECT", "request": [{"op": "remove", "target": "header", "name": "X-A"}]}
                """)).containsExactly("projects");
        assertThat(errors("""
                {"scope": "SUBTASK",
                 "request": [{"op": "put", "target": "header", "name": "X-A", "value": 1},
                             {"op": "set", "target": "cookie", "name": "a", "value": 1},
                             {"op": "set", "target": "header", "name": "Authorization", "value": "x"},
                             {"op": "set", "target": "header", "name": "X-User-Id", "value": "x"},
                             {"op": "set", "target": "body", "name": "a..b", "value": 1},
                             {"op": "remove", "target": "query", "name": "a", "value": 1},
                             {"op": "set", "target": "query", "name": "a"},
                             {"op": "set", "target": "header", "name": "X-Took", "value": "${durationMs}"},
                             {"op": "set", "target": "header", "name": "X-B", "value": "${backend.status}"},
                             {"op": "set", "target": "header", "name": "X-C", "value": "${header.Cookie}"},
                             {"op": "set", "target": "header", "name": "X-D", "value": "${now:qqqqqq}"},
                             {"op": "set", "target": "header", "name": "X-E", "value": "${alarm.values}"}],
                 "response": [{"op": "set", "target": "query", "name": "a", "value": 1},
                              {"op": "set", "target": "header", "name": "Content-Length", "value": 1},
                              {"op": "set", "target": "header", "name": "x-request-id", "value": 1}]}
                """)).containsExactlyInAnyOrder("request[0].op", "request[1].target", "request[2].name",
                "request[3].name", "request[4].name", "request[5].value", "request[6].value", "request[7].value",
                "request[8].value", "request[9].value", "request[10].value", "request[11].value", "response[0].target",
                "response[1].name", "response[2].name");
    }

    @Test
    void aSubtaskGetsGlobalThenProjectThenItsOwnPluginsByName() {
        List<Plugin> all = List.of(
                plugin("zeta", "{\"scope\": \"GLOBAL\", \"request\": [{\"op\": \"remove\", \"target\": \"header\", \"name\": \"X-A\"}]}"),
                plugin("alpha", "{\"scope\": \"GLOBAL\", \"request\": [{\"op\": \"remove\", \"target\": \"header\", \"name\": \"X-A\"}]}"),
                plugin("off", "{\"scope\": \"GLOBAL\", \"request\": [{\"op\": \"remove\", \"target\": \"header\", \"name\": \"X-A\"}]}"),
                plugin("orders", """
                        {"scope": "PROJECT", "projects": ["Order-Service"],
                         "request": [{"op": "remove", "target": "header", "name": "X-A"}]}
                        """),
                plugin("chosen", "{\"scope\": \"SUBTASK\", \"request\": [{\"op\": \"remove\", \"target\": \"header\", \"name\": \"X-A\"}]}"),
                plugin("other", "{\"scope\": \"SUBTASK\", \"request\": [{\"op\": \"remove\", \"target\": \"header\", \"name\": \"X-A\"}]}"));
        RouteDefinition subtask = definition("{\"plugins\": [\"Chosen\"], \"disabledPlugins\": [\"OFF\"]}");

        assertThat(Plugins.select(all, "order-service", subtask)).extracting(Plugin::name)
                .containsExactly("alpha", "zeta", "orders", "chosen");
        assertThat(Plugins.select(all, "billing", subtask)).extracting(Plugin::name)
                .containsExactly("alpha", "zeta", "chosen");
        assertThat(Plugins.select(all, "billing", definition("{}"))).extracting(Plugin::name)
                .containsExactly("alpha", "off", "zeta");
    }

    @Test
    void requestOperationsLeaveWhatTheDefinitionWroteAlone() {
        RouteDefinition subtask = definition("""
                {"request": {"fields": [{"name": "id", "type": "integer", "required": true}],
                             "headers": {"X-Channel": "WEB"}, "queryParams": {"page": "1"},
                             "body": {"mode": "TEMPLATE", "template": {"id": "${request.id}", "meta": {"source": "web"}}}}}
                """);
        BackendRequest built = BackendRequestBuilder.build(HttpMethodName.POST, PathTemplate.parse("/notes"), subtask,
                "http://svc", context("{\"id\": 7}", "u1"));
        Plugin stamp = plugin("stamp", """
                {"scope": "GLOBAL",
                 "request": [{"op": "set", "target": "header", "name": "x-channel", "value": "OTHER"},
                             {"op": "set", "target": "header", "name": "X-Request-Time", "value": "${now}"},
                             {"op": "set", "target": "header", "name": "X-Sicil", "value": "${user.id}"},
                             {"op": "remove", "target": "header", "name": "X-Request-Id"},
                             {"op": "set", "target": "query", "name": "page", "value": "9"},
                             {"op": "setIfAbsent", "target": "query", "name": "lang", "value": "tr"},
                             {"op": "set", "target": "body", "name": "meta.source", "value": "plugin"},
                             {"op": "set", "target": "body", "name": "id", "value": 0},
                             {"op": "set", "target": "body", "name": "meta.requestDate", "value": "${today}"},
                             {"op": "set", "target": "body", "name": "audit.route", "value": "${route.maintask}/${route.subtask}"},
                             {"op": "setIfAbsent", "target": "body", "name": "audit.permissions", "value": "${user.permissions}"},
                             {"op": "set", "target": "body", "name": "audit.missing", "value": "${request.nope}"}]}
                """);

        Plugins.RequestResult result = Plugins.applyRequest(built, List.of(stamp), subtask.request(),
                context("{\"id\": 7}", "u1").resolver());

        BackendRequest request = result.request();
        assertThat(result.applied()).containsExactly(new Plugin.Applied("stamp", 3));
        assertThat(request.headers().get("X-Channel")).containsExactly("WEB");
        assertThat(request.headers().get("X-Request-Time")).containsExactly("2026-10-28T12:15:30.123");
        assertThat(request.headers().get("X-Sicil")).containsExactly("u1");
        assertThat(request.headers().get("X-uid")).containsExactly("u1");
        assertThat(request.headers().get("X-Request-Id")).containsExactly("req-1");
        assertThat(request.uri().toString()).isEqualTo("http://svc/notes?page=1&lang=tr");
        JsonNode expected = Json.parse("""
                {"id": 7, "meta": {"source": "web", "requestDate": "2026-10-28"},
                 "audit": {"route": "order-service/add_note", "permissions": ["ORDER_WRITE", "ORDER_READ"]}}
                """);
        assertThat(request.previewBody()).isEqualTo(expected);
        assertThat(Json.parse(new String(request.body(), StandardCharsets.UTF_8))).isEqualTo(expected);
    }

    @Test
    void aPassThroughBodyCanBeChangedAnywhere() {
        RouteDefinition subtask = definition("{\"request\": {\"body\": {\"mode\": \"PASS_THROUGH\"}}}");
        RequestContext context = context("{\"maintask\": \"m\", \"subtask\": \"s\", \"a\": {\"b\": 1}}", null);
        BackendRequest built = BackendRequestBuilder.build(HttpMethodName.POST, PathTemplate.parse("/x"), subtask,
                "http://svc", context);
        Plugin plugin = plugin("pass", """
                {"scope": "GLOBAL",
                 "request": [{"op": "set", "target": "body", "name": "a.b", "value": 2},
                             {"op": "remove", "target": "body", "name": "a.c"},
                             {"op": "set", "target": "body", "name": "a.b.c", "value": 3},
                             {"op": "set", "target": "header", "name": "X-User", "value": "${user.id}"}]}
                """);

        BackendRequest request = Plugins.applyRequest(built, List.of(plugin), subtask.request(), context.resolver())
                .request();

        assertThat(request.previewBody()).isEqualTo(Json.parse("{\"a\": {\"b\": 2}}"));
        // without a user the value is missing and the operation is skipped
        assertThat(request.headers()).doesNotContainKey("X-User");
    }

    @Test
    void responseOperationsSeeTheAnswerAndTheTimings() {
        Plugin timing = plugin("timing", """
                {"scope": "GLOBAL",
                 "response": [{"op": "set", "target": "header", "name": "X-Response-Time", "value": "${durationMs}"},
                              {"op": "set", "target": "header", "name": "X-Backend", "value": "${backend.status} ${backend.header.X-Trace} ${backend.durationMs}"},
                              {"op": "remove", "target": "header", "name": "content-type"},
                              {"op": "set", "target": "body", "name": "meta.outcome", "value": "${outcome}"},
                              {"op": "setIfAbsent", "target": "body", "name": "meta.receivedAt", "value": "${requestReceivedAt}"},
                              {"op": "set", "target": "body", "name": "status", "value": "${backend.status}"}]}
                """);
        Plugins.ResponseValues values = new Plugins.ResponseValues(201, Map.of("x-trace", "t-1"), "SUCCESS", 180L,
                RECEIVED, scope());
        var resolver = Plugins.responseResolver(context("{}", "u1").resolver(), values);

        Plugins.ResponseResult json = Plugins.applyResponse(Map.of("Content-Type", "application/json"),
                Json.parse("{\"data\": 1, \"meta\": {\"receivedAt\": \"kept\"}}"), List.of(timing), resolver);
        assertThat(json.headers()).containsExactly(Map.entry("X-Response-Time", "250"),
                Map.entry("X-Backend", "201 t-1 180"));
        assertThat(json.body()).isEqualTo(Json.parse("""
                {"data": 1, "meta": {"receivedAt": "kept", "outcome": "SUCCESS"}, "status": 201}
                """));

        Plugins.ResponseResult file = Plugins.applyResponse(Map.of("Content-Disposition", "attachment"), null,
                List.of(timing), resolver);
        assertThat(file.body()).isNull();
        assertThat(file.headers()).containsKeys("Content-Disposition", "X-Response-Time");
        assertThat(Plugins.applyResponse(Map.of(), Json.parse("[1]"), List.of(timing), resolver).body())
                .isEqualTo(Json.parse("[1]"));
    }

    @Test
    void definitionsUseTheRequestWidePlaceholdersToo() {
        String json = """
                {"request": {"headers": {"X-Date": "${now:dd.MM.yyyy HH:mm}", "X-Trace": "${traceId}",
                                         "X-Env": "${environment}", "X-Ip": "${client.ip}"},
                             "body": {"mode": "TEMPLATE", "template": {"day": "${today}", "at": "${epochMillis}",
                                                                       "perms": "${user.permissions}",
                                                                       "version": "${route.version}"}}}}
                """;
        assertThat(RouteValidator.validate("POST", "/x", Json.parse(json), READABLE)).isEmpty();
        assertThat(RouteValidator.validate("POST", "/x", Json.parse("""
                {"request": {"headers": {"X-A": "${now:qqqqqq}", "X-B": "${route.name}", "X-C": "${durationMs}"}}}
                """), READABLE)).extracting(FieldError::field).containsExactlyInAnyOrder(
                "definition.request.headers.X-A", "definition.request.headers.X-B", "definition.request.headers.X-C");

        BackendRequest request = BackendRequestBuilder.build(HttpMethodName.POST, PathTemplate.parse("/x"),
                definition(json), "http://svc", context("{}", "u1"));
        assertThat(request.headers().get("X-Date")).containsExactly("28.10.2026 12:15");
        assertThat(request.headers().get("X-Env")).containsExactly("PREPROD");
        assertThat(request.headers().get("X-Ip")).containsExactly("10.0.0.5");
        assertThat(request.previewBody()).isEqualTo(Json.parse("""
                {"day": "2026-10-28", "at": %d, "perms": ["ORDER_WRITE", "ORDER_READ"], "version": 4}
                """.formatted(NOW.toEpochMilli())));

        ObjectNode body = (ObjectNode) Json.parse("{}");
        RequestContext noScope = new RequestContext(body, body, name -> null, READABLE, null, null, "r1");
        assertThat(BackendRequestBuilder.build(HttpMethodName.POST, PathTemplate.parse("/x"), definition(json),
                "http://svc", noScope).headers()).doesNotContainKeys("X-Date", "X-Env");
    }
}
