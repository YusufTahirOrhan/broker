package com.acme.broker.core.spec;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.acme.broker.core.json.Json;
import com.acme.broker.core.route.RouteValidator;
import com.acme.broker.core.testing.CoreFixtures;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class OpenApiSpecTest {

    private static JsonNode sampleSpec() {
        return CoreFixtures.sampleSpec();
    }

    private static Map<String, OpenApiSpec.Operation> byKey(JsonNode spec) {
        return CoreFixtures.operationsByKey(spec);
    }

    @Test
    void readsOperationsSortedByPathAndMethod() {
        List<String> keys = OpenApiSpec.operations(sampleSpec()).stream().map(OpenApiSpec.Operation::key).toList();
        assertThat(keys).containsExactly("POST /files", "DELETE /orders/{id}", "GET /orders/{id}",
                "POST /orders/{id}/notes", "GET /reports/{id}/excel");
    }

    @Test
    void refsAreResolvedAndCyclesCut() {
        Map<String, OpenApiSpec.Operation> operations = byKey(sampleSpec());
        JsonNode noteSchema = operations.get("POST /orders/{id}/notes").requestBody()
                .get("content").get("application/json").get("schema");
        assertThat(Json.fieldNames(noteSchema.get("properties"))).containsExactly("text", "authorId", "tags");

        JsonNode order = operations.get("GET /orders/{id}").responses().get("200").get("content").get("*/*")
                .get("schema").get("properties").get("items").get("items").get("properties").get("order");
        assertThat(order.get("x-unresolved").booleanValue()).isTrue();
    }

    @Test
    void fingerprintChangesWhenTheRequestDtoChanges() {
        JsonNode spec = sampleSpec();
        Map<String, OpenApiSpec.Operation> before = byKey(spec);
        ObjectNode properties = (ObjectNode) spec.get("components").get("schemas").get("NoteRequest").get("properties");
        properties.putObject("priority").put("type", "integer");
        Map<String, OpenApiSpec.Operation> after = byKey(spec);

        assertThat(after.get("POST /orders/{id}/notes").fingerprint())
                .isNotEqualTo(before.get("POST /orders/{id}/notes").fingerprint());
        assertThat(after.get("GET /orders/{id}").fingerprint()).isEqualTo(before.get("GET /orders/{id}").fingerprint());
        assertThat(before.get("GET /orders/{id}").fingerprint()).hasSize(64);
    }

    @Test
    void proposalMapsEveryParameterOneToOne() {
        ObjectNode proposal = SpecProposal.propose(byKey(sampleSpec()).get("POST /orders/{id}/notes"));
        assertThat(proposal.get("pathTemplate").stringValue()).isEqualTo("/orders/{id}/notes");
        JsonNode request = proposal.get("definition").get("request");
        assertThat(request.get("fields")).isEqualTo(Json.parse("""
                [{"name": "id", "type": "integer", "required": true},
                 {"name": "notify", "type": "boolean", "required": false},
                 {"name": "text", "type": "string", "required": true},
                 {"name": "authorId", "type": "string", "required": false},
                 {"name": "tags", "type": "array", "required": false}]
                """));
        assertThat(request.get("pathVariables")).isEqualTo(Json.parse("{\"id\": \"${request.id}\"}"));
        assertThat(request.get("body").get("template")).isEqualTo(Json.parse("""
                {"text": "${request.text}", "authorId": "${request.authorId}", "tags": "${request.tags}"}
                """));
        assertValid(proposal);
    }

    @Test
    void proposalDetectsFilesAndMultipart() {
        Map<String, OpenApiSpec.Operation> operations = byKey(sampleSpec());
        ObjectNode excel = SpecProposal.propose(operations.get("GET /reports/{id}/excel"));
        assertThat(excel.get("definition").get("response").get("type").stringValue()).isEqualTo("BINARY");
        assertThat(excel.get("definition").get("request").get("body").get("mode").stringValue()).isEqualTo("NONE");
        assertValid(excel);

        ObjectNode upload = SpecProposal.propose(operations.get("POST /files"));
        JsonNode body = upload.get("definition").get("request").get("body");
        assertThat(body.get("mode").stringValue()).isEqualTo("MULTIPART");
        assertThat(body.get("parts").get(0).get("file").booleanValue()).isTrue();
        assertValid(upload);
    }

    private static void assertValid(ObjectNode proposal) {
        assertThat(RouteValidator.validate(proposal.get("httpMethod").stringValue(),
                proposal.get("pathTemplate").stringValue(), proposal.get("definition"),
                RouteValidator.Context.of(List.of()))).isEmpty();
    }
}
