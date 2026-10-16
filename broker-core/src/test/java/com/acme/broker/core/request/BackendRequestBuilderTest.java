package com.acme.broker.core.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import com.acme.broker.core.json.Json;
import com.acme.broker.core.route.HttpMethodName;
import com.acme.broker.core.route.PathTemplate;
import com.acme.broker.core.route.RouteDefinition;
import com.acme.broker.core.route.RouteDefinitionParser;
import com.acme.broker.core.route.RouteValidator;
import com.acme.broker.core.testing.CoreFixtures;
import com.acme.broker.core.validation.FieldError;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class BackendRequestBuilderTest {

    private static final RouteValidator.Context READABLE = RouteValidator.Context.of(List.of("Accept-Language"));

    private static RouteDefinition definition(String json) {
        RouteDefinitionParser.Parsed parsed = RouteDefinitionParser.parse(Json.parse(json));
        assertThat(parsed.errors()).isEmpty();
        return parsed.definition();
    }

    private static BackendRequest build(String method, String path, String definitionJson, String bodyJson,
                                        Map<String, String> headers, String userId) {
        return build(method, path, definitionJson, bodyJson, headers, userId, null);
    }

    private static BackendRequest build(String method, String path, String definitionJson, String bodyJson,
                                        Map<String, String> headers, String userId, String permission) {
        RouteDefinition definition = definition(definitionJson);
        ObjectNode body = (ObjectNode) Json.parse(bodyJson);
        RequestBodyValidator.Result validated = RequestBodyValidator.validate(body, definition.request().fields());
        assertThat(validated.errors()).isEmpty();
        TreeMap<String, String> caseInsensitive = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        caseInsensitive.putAll(headers);
        RequestContext context = new RequestContext(body, validated.effectiveBody(), caseInsensitive::get, READABLE,
                userId, permission, "0192f1c4-7a3b-7c1e-9d2f-5b8e4a1c3d20");
        return BackendRequestBuilder.build(HttpMethodName.valueOf(method), PathTemplate.parse(path), definition,
                "http://order-service.dev.svc:8080/", context);
    }

    @Test
    void designDocumentExample() {
        BackendRequest request = build("POST", "/orders/{id}/notes", CoreFixtures.ADD_ORDER_NOTE, """
                {"maintask": "order-service", "subtask": "add_order_note", "application": "web", "uid": "u12345",
                 "siparisNo": 981, "notMetni": "Kapıya bırakın"}
                """, Map.of("accept-language", "tr-TR"), "u12345");

        assertThat(request.uri().toString()).isEqualTo("http://order-service.dev.svc:8080/orders/981/notes?notify=false");
        assertThat(request.headers()).containsEntry("X-Channel", List.of("WEB"))
                .containsEntry("Accept-Language", List.of("tr-TR"))
                .containsEntry("X-uid", List.of("u12345"))
                .containsEntry("Content-Type", List.of("application/json"));
        JsonNode body = Json.parse(request.body());
        assertThat(body).isEqualTo(Json.parse("""
                {"text": "Kapıya bırakın", "authorId": "u12345", "source": "WEB"}
                """));
    }

    @Test
    void userComesFromTheSessionNotFromTheBody() {
        BackendRequest request = build("POST", "/orders/{id}/notes", CoreFixtures.ADD_ORDER_NOTE, """
                {"uid": "someone-else", "siparisNo": 1, "notMetni": "x"}
                """, Map.of(), "u12345");
        assertThat(request.headers().get("X-uid")).containsExactly("u12345");
        assertThat(Json.text(Json.parse(request.body()).get("authorId"))).isEqualTo("u12345");
    }

    @Test
    void optionalValuesWithoutAValueAreNotSent() {
        BackendRequest request = build("GET", "/orders", """
                {"request": {"fields": [{"name": "status", "type": "string"}, {"name": "ids", "type": "array"}],
                             "queryParams": {"status": "${request.status}", "id": "${request.ids}"},
                             "headers": {"Accept-Language": "${header.Accept-Language}"}}}
                """, """
                {"ids": [3, 4]}
                """, Map.of(), null);
        assertThat(request.uri().toString()).isEqualTo("http://order-service.dev.svc:8080/orders?id=3&id=4");
        assertThat(request.headers()).doesNotContainKeys("Accept-Language", "X-uid", "Content-Type");
        assertThat(request.hasBody()).isFalse();
    }

    @Test
    void pathValuesAreEncodedAsOneSegment() {
        BackendRequest request = build("GET", "/files/{name}", """
                {"request": {"fields": [{"name": "ad", "type": "string", "required": true}],
                             "pathVariables": {"name": "${request.ad}"}}}
                """, """
                {"ad": "a/b ç?.txt"}
                """, Map.of(), null);
        assertThat(request.uri().getRawPath()).isEqualTo("/files/a%2Fb%20%C3%A7%3F.txt");
    }

    @Test
    void passThroughDropsOnlyTheEnvelope() {
        BackendRequest request = build("POST", "/orders", """
                {"request": {"body": {"mode": "PASS_THROUGH"}}}
                """, """
                {"maintask": "m", "subtask": "s", "application": "web", "uid": "u", "a": 1, "b": {"c": true}}
                """, Map.of(), "u12345");
        assertThat(Json.parse(request.body())).isEqualTo(Json.parse("{\"a\": 1, \"b\": {\"c\": true}}"));
    }

    @Test
    void defaultsAreAppliedBeforeMapping() {
        BackendRequest request = build("POST", "/orders/{id}/notes", CoreFixtures.ADD_ORDER_NOTE, """
                {"siparisNo": 7, "notMetni": "n", "bildir": true}
                """, Map.of(), "u");
        assertThat(request.uri().getQuery()).isEqualTo("notify=true");
    }

    @Test
    void multipartCarriesFilesAndText() {
        String content = Base64.getEncoder().encodeToString("merhaba".getBytes(StandardCharsets.UTF_8));
        BackendRequest request = build("POST", "/upload", """
                {"request": {"fields": [{"name": "dosya", "type": "object", "required": true},
                                        {"name": "aciklama", "type": "string"}],
                             "body": {"mode": "MULTIPART", "parts": [
                               {"name": "file", "value": "${request.dosya}", "file": true},
                               {"name": "note", "value": "${request.aciklama}"}]}}}
                """, """
                {"dosya": {"fileName": "a.txt", "contentType": "text/plain", "base64": "%s"}, "aciklama": "not"}
                """.formatted(content), Map.of(), null);
        String body = new String(request.body(), StandardCharsets.UTF_8);
        assertThat(request.contentType()).startsWith("multipart/form-data; boundary=");
        assertThat(body).contains("name=\"file\"; filename=\"a.txt\"", "Content-Type: text/plain", "merhaba",
                "name=\"note\"", "\r\n\r\nnot\r\n");
        assertThat(request.previewBody().get(0).get("size").intValue()).isEqualTo(7);
    }

    @Test
    void bodyValidationReportsMissingAndWrongTypes() {
        RouteDefinition definition = definition(CoreFixtures.ADD_ORDER_NOTE);
        RequestBodyValidator.Result result = RequestBodyValidator.validate(
                (ObjectNode) Json.parse("{\"siparisNo\": \"981\"}"), definition.request().fields());
        assertThat(result.errors()).extracting(FieldError::field).containsExactly("siparisNo", "notMetni");
        assertThat(result.errors().getFirst().message()).isEqualTo("integer bekleniyor");
    }

    @Test
    void nestedFieldsUseDottedPaths() {
        RouteDefinition definition = definition("""
                {"request": {"fields": [{"name": "adres.il", "type": "string", "required": true},
                                        {"name": "adres.ulke", "type": "string", "default": "TR"}]}}
                """);
        RequestBodyValidator.Result result = RequestBodyValidator.validate(
                (ObjectNode) Json.parse("{\"adres\": {\"il\": \"Ankara\"}}"), definition.request().fields());
        assertThat(result.errors()).isEmpty();
        assertThat(Json.text(result.effectiveBody().get("adres").get("ulke"))).isEqualTo("TR");

        result = RequestBodyValidator.validate((ObjectNode) Json.parse("{\"adres\": \"Ankara\"}"),
                definition.request().fields());
        assertThat(result.errors()).extracting(FieldError::field).containsExactly("adres.il");
    }

    @Test
    void userPermissionIsTheFirstRoutePermissionTheUserHasAndIsLeftOutWithoutOne() {
        String definition = """
                {"request": {"fields": [{"name": "sorgu", "type": "string", "required": true}],
                             "headers": {"X-Perm": "${user.permission}"},
                             "body": {"mode": "TEMPLATE", "template": {"q": "${request.sorgu}", "perm": "${user.permission}"}}}}
                """;
        String body = "{\"sorgu\": \"x\"}";

        BackendRequest withPermission = build("POST", "/sorgu", definition, body, Map.of(), "u1", "SORGU_TEMEL");
        assertThat(withPermission.headers().get("X-Perm")).containsExactly("SORGU_TEMEL");
        assertThat(Json.parse(withPermission.body())).isEqualTo(Json.parse("{\"q\": \"x\", \"perm\": \"SORGU_TEMEL\"}"));

        BackendRequest without = build("POST", "/sorgu", definition, body, Map.of(), null, null);
        assertThat(without.headers()).doesNotContainKey("X-Perm");
        assertThat(Json.parse(without.body())).isEqualTo(Json.parse("{\"q\": \"x\"}"));
    }

    @Test
    void theFirstPermissionFollowsTheRoutesOrder() {
        List<String> route = List.of("SORGU_TEMEL", "SORGU_ILERI", "CRM_AGENT");

        assertThat(RequestContext.firstPermission(route, Set.of("SORGU_ILERI", "SORGU_TEMEL"))).isEqualTo("SORGU_TEMEL");
        assertThat(RequestContext.firstPermission(route, Set.of("CRM_AGENT", "SORGU_ILERI"))).isEqualTo("SORGU_ILERI");
        assertThat(RequestContext.firstPermission(route, Set.of("CRM_AGENT"))).isEqualTo("CRM_AGENT");
        assertThat(RequestContext.firstPermission(route, Set.of("OTHER"))).isNull();
        assertThat(RequestContext.firstPermission(route, null)).isNull();
        assertThat(RequestContext.firstPermission(List.of(), Set.of("A"))).isNull();
    }

    @Test
    void missingPathValueFailsTheBuild() {
        RouteDefinition definition = definition("""
                {"request": {"pathVariables": {"id": "${request.id}"}}}
                """);
        RequestContext context = new RequestContext(Json.object(), Json.object(), name -> null, READABLE, null, null,
                "r");
        assertThatThrownBy(() -> BackendRequestBuilder.build(HttpMethodName.GET, PathTemplate.parse("/o/{id}"),
                definition, "http://x", context)).isInstanceOf(RequestBuildException.class);
    }
}
