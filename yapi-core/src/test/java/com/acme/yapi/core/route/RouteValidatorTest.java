package com.acme.yapi.core.route;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.testing.CoreFixtures;
import com.acme.yapi.core.validation.FieldError;

import tools.jackson.databind.JsonNode;

class RouteValidatorTest {

    private static final String ADD_ORDER_NOTE = CoreFixtures.ADD_ORDER_NOTE;

    private final RouteValidator.Context context = RouteValidator.Context.of(List.of("Accept-Language", "X-Clientip"));

    private List<FieldError> validate(String method, String path, String definition) {
        return RouteValidator.validate(method, path, Json.parse(definition), context);
    }

    @Test
    void designDocumentExampleIsValid() {
        assertThat(validate("POST", "/orders/{id}/notes", ADD_ORDER_NOTE)).isEmpty();
    }

    @Test
    void everyPathVariableMustBeMappedOnce() {
        List<FieldError> errors = validate("POST", "/orders/{id}/notes/{noteId}", ADD_ORDER_NOTE);
        assertThat(errors).extracting(FieldError::field).contains("definition.request.pathVariables.noteId");

        errors = validate("POST", "/orders/notes", ADD_ORDER_NOTE);
        assertThat(errors).extracting(FieldError::field).contains("definition.request.pathVariables.id");
    }

    @Test
    void pathVariableNeedsAFieldThatIsAlwaysPresent() {
        String definition = """
                {"request": {"fields": [{"name": "id", "type": "integer"}],
                             "pathVariables": {"id": "${request.id}"}}}
                """;
        assertThat(validate("GET", "/orders/{id}", definition))
                .extracting(FieldError::field).containsExactly("definition.request.pathVariables.id");
    }

    @Test
    void identityHeadersCannotBeDefined() {
        String definition = """
                {"request": {"headers": {"Authorization": "x", "X-User-Id": "${user.id}", "X-uid": "1", "X-Channel": "WEB",
                                         "X-User-Agent": "${header.X-Clientip}"}}}
                """;
        assertThat(validate("GET", "/a", definition)).extracting(FieldError::field).containsExactlyInAnyOrder(
                "definition.request.headers.Authorization", "definition.request.headers.X-User-Id",
                "definition.request.headers.X-uid");
    }

    @Test
    void getCannotHaveABody() {
        String definition = """
                {"request": {"body": {"mode": "PASS_THROUGH"}}}
                """;
        assertThat(validate("GET", "/a", definition))
                .extracting(FieldError::field).containsExactly("definition.request.body.mode");
        assertThat(validate("POST", "/a", definition)).isEmpty();
    }

    @Test
    void retryOnlyForGet() {
        String definition = """
                {"retry": {"maxAttempts": 2}}
                """;
        assertThat(validate("GET", "/a", definition)).isEmpty();
        assertThat(validate("POST", "/a", definition))
                .extracting(FieldError::field).containsExactly("definition.retry.maxAttempts");
    }

    @Test
    void placeholdersMustPointToDefinedFieldsAndReadableHeaders() {
        String definition = """
                {"request": {"fields": [{"name": "adres", "type": "object", "required": true}],
                             "queryParams": {"il": "${request.adres.il}", "x": "${request.yok}",
                                             "lang": "${header.Accept-Language}", "secret": "${header.X-Token}",
                                             "who": "${user.name}", "status": "${backend.status}",
                                             "perm": "${user.permission}"}}}
                """;
        List<FieldError> errors = validate("GET", "/a", definition);
        assertThat(errors).extracting(FieldError::field).containsExactlyInAnyOrder(
                "definition.request.queryParams.x", "definition.request.queryParams.secret",
                "definition.request.queryParams.who", "definition.request.queryParams.status");
    }

    @Test
    void envelopeFieldsAreReadWithoutAFieldDefinitionButNotInThePath() {
        String definition = """
                {"request": {"fields": [{"name": "password", "type": "string", "required": true}],
                             "pathVariables": {"id": "${request.uid}"},
                             "headers": {"X-App": "${request.application}", "X-Task": "${request.maintask}/${request.subtask}"},
                             "body": {"mode": "TEMPLATE",
                                      "template": {"uid": "${request.uid}", "password": "${request.password}"}}}}
                """;
        assertThat(validate("POST", "/login/{id}", definition)).extracting(FieldError::field)
                .containsExactly("definition.request.pathVariables.id");
        assertThat(validate("POST", "/login", definition.replace("\"pathVariables\": {\"id\": \"${request.uid}\"},", "")))
                .isEmpty();
    }

    @Test
    void structuralProblemsAreReportedWithPaths() {
        String definition = """
                {"request": {"fields": [{"name": "uid", "type": "string"},
                                        {"name": "a", "type": "date"},
                                        {"name": "b", "type": "integer", "required": true, "default": 1},
                                        {"name": "c", "type": "integer", "default": "x"}]},
                 "timeoutMs": 10, "unknown": 1}
                """;
        assertThat(validate("GET", "/a", definition)).extracting(FieldError::field).containsExactlyInAnyOrder(
                "definition.unknown", "definition.request.fields[0].name", "definition.request.fields[1].type",
                "definition.request.fields[2].default", "definition.request.fields[3].default",
                "definition.timeoutMs");
    }

    @Test
    void invalidMethodAndPathAreReported() {
        assertThat(validate("get", "orders", "{}")).extracting(FieldError::field)
                .containsExactlyInAnyOrder("httpMethod", "pathTemplate");
    }

    @Test
    void multipartFilePartMustBindToAnObjectField() {
        String definition = """
                {"request": {"fields": [{"name": "dosya", "type": "object", "required": true},
                                        {"name": "aciklama", "type": "string"}],
                             "body": {"mode": "MULTIPART", "parts": [
                               {"name": "file", "value": "${request.dosya}", "file": true},
                               {"name": "note", "value": "${request.aciklama}", "file": true}]}}}
                """;
        assertThat(validate("POST", "/upload", definition))
                .extracting(FieldError::field).containsExactly("definition.request.body.parts[1].value");
    }

    @Test
    void parsedDefinitionHasDefaults() {
        JsonNode node = Json.parse("{}");
        RouteDefinition definition = RouteDefinitionParser.parse(node).definition();
        assertThat(definition.timeoutMs()).isEqualTo(5000);
        assertThat(definition.maxAttempts()).isEqualTo(1);
        assertThat(definition.responseType()).isEqualTo(ResponseType.JSON);
        assertThat(definition.request().body().mode()).isEqualTo(BodyMode.NONE);
        assertThat(definition.logging().effectiveMaskFields()).containsExactly("password");
    }
}
