package com.acme.yapi.core.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.validation.FieldError;

import tools.jackson.databind.JsonNode;

/**
 * A profile that does what a project's own response class used to: an error envelope for 2xx bodies with an error
 * field, built from fields of the body; another for one error code; the service's status on errors; a fixed answer
 * when the service did not answer.
 */
class ResponseProfileCasesTest {

    private static final String ENVELOPE = """
            "message_title": "${backend.body.message}", "message": "${backend.body.details}",
            "status": "${backend.body.status-code}", "timestamp": "${backend.body.timestamp}"
            """;

    static final String PROFILE = """
            {"success": {
               "cases": [
                 {"when": [{"value": "${backend.body.status-code}", "equals": 409},
                           {"value": "${backend.body.ref_id}", "exists": true}],
                  "httpStatus": 200, "body": {"data": {"ref_id": "${backend.body.ref_id}"}, %1$s}},
                 {"when": [{"value": "${backend.body.status-code}", "exists": true}],
                  "httpStatus": 200, "body": {"data": null, %1$s}}],
               "httpStatus": 200, "body": {"status": 200, "data": "${backend.body}"}},
             "error": {
               "cases": [
                 {"when": [{"value": "${backend.status}", "exists": false}],
                  "httpStatus": 400, "body": {"status": 400, "reason": "Servis hatası: yanıt yok"}}],
               "httpStatus": "${backend.status}",
               "body": {"status": "${backend.status}", "reason": "Servis hatası: ${backend.body}"}}}
            """.formatted(ENVELOPE);

    private final ResponseProfile profile = parse(PROFILE);

    private static ResponseProfile parse(String definition) {
        ResponseProfile.Parsed parsed = ResponseProfile.parse(Json.parse(definition));
        assertThat(parsed.errors()).isEmpty();
        return parsed.profile();
    }

    private ResponseProfile.Rendered render(ResponseProfile profile, Integer status, String body, Outcome outcome) {
        return profile.evaluate(new ResponseProfile.BackendResult(status, body == null ? null : Json.parse(body),
                outcome, "r1", Map.of("x-trace", "t-9")));
    }

    private ResponseProfile.Rendered success(String body) {
        return render(profile, 200, body, Outcome.SUCCESS);
    }

    @Test
    void aSuccessfulBodyWithAnErrorFieldGetsTheErrorEnvelope() {
        ResponseProfile.Rendered rendered = success("""
                {"status-code": 422, "message": "Doğrulama", "timestamp": "2026-10-28T10:00:00"}
                """);

        assertThat(rendered.matchedCase()).isEqualTo(1);
        assertThat(rendered.template()).isEqualTo("success");
        assertThat(rendered.response().status()).isEqualTo(200);
        // a field the body does not have is written as null, not left out
        assertThat(rendered.response().body()).isEqualTo(Json.parse("""
                {"data": null, "message_title": "Doğrulama", "message": null, "status": 422,
                 "timestamp": "2026-10-28T10:00:00"}
                """));
    }

    @Test
    void oneCodeWithAnotherFieldGetsItsOwnEnvelope() {
        ResponseProfile.Rendered rendered = success("""
                {"status-code": 409, "ref_id": 77, "message": "Çakışma", "details": "Kayıt var"}
                """);

        assertThat(rendered.matchedCase()).isZero();
        assertThat(rendered.response().body()).isEqualTo(Json.parse("""
                {"data": {"ref_id": 77}, "message_title": "Çakışma", "message": "Kayıt var", "status": 409,
                 "timestamp": null}
                """));
        // the comparison keeps the JSON type: the text "409" is not the number 409
        assertThat(success("{\"status-code\": \"409\", \"ref_id\": 77}").matchedCase()).isEqualTo(1);
        assertThat(success("{\"status-code\": 409}").matchedCase()).isEqualTo(1);
    }

    @Test
    void otherSuccessfulBodiesAreWrapped() {
        ResponseProfile.Rendered object = success("{\"id\": 5}");
        assertThat(object.matchedCase()).isNull();
        assertThat(object.response().body()).isEqualTo(Json.parse("{\"status\": 200, \"data\": {\"id\": 5}}"));
        assertThat(success("[{\"status-code\": 1}]").response().body())
                .isEqualTo(Json.parse("{\"status\": 200, \"data\": [{\"status-code\": 1}]}"));
        assertThat(success("\"metin\"").response().body()).isEqualTo(Json.parse("{\"status\": 200, \"data\": \"metin\"}"));
        assertThat(render(profile, 204, null, Outcome.SUCCESS).response().body())
                .isEqualTo(Json.parse("{\"status\": 200, \"data\": null}"));
    }

    @Test
    void errorsKeepTheServicesStatusAndEmbedTheBodyAsText() {
        ResponseProfile.Rendered notFound = render(profile, 404, "{\"code\": \"YOK\", \"items\": [1, 2]}",
                Outcome.BUSINESS_ERROR);

        assertThat(notFound.template()).isEqualTo("error");
        assertThat(notFound.matchedCase()).isNull();
        assertThat(notFound.response().status()).isEqualTo(404);
        assertThat(notFound.response().body()).isEqualTo(Json.parse("""
                {"status": 404, "reason": "Servis hatası: {\\"code\\":\\"YOK\\",\\"items\\":[1,2]}"}
                """));
    }

    @Test
    void aServerErrorBodyStaysHiddenUnlessTheProfileExposesIt() {
        String stackTrace = "{\"trace\": \"java.lang.NullPointerException at ...\"}";

        ResponseProfile.Rendered hidden = render(profile, 500, stackTrace, Outcome.TECHNICAL_ERROR);
        assertThat(hidden.response().status()).isEqualTo(500);
        assertThat(hidden.response().body()).isEqualTo(Json.parse("{\"status\": 500, \"reason\": \"Servis hatası: \"}"));

        ResponseProfile exposing = parse(PROFILE.replaceFirst("\\{\"success\"", "{\"exposeServerErrorBody\": true, \"success\""));
        assertThat(render(exposing, 500, stackTrace, Outcome.TECHNICAL_ERROR).response().body().get("reason").stringValue())
                .isEqualTo("Servis hatası: {\"trace\":\"java.lang.NullPointerException at ...\"}");
    }

    @Test
    void withoutAnAnswerAFixedResponseIsReturned() {
        for (Outcome outcome : new Outcome[]{Outcome.TIMEOUT, Outcome.TECHNICAL_ERROR}) {
            ResponseProfile.Rendered rendered = render(profile, null, null, outcome);
            assertThat(rendered.matchedCase()).isZero();
            assertThat(rendered.response().status()).isEqualTo(400);
            assertThat(rendered.response().body())
                    .isEqualTo(Json.parse("{\"status\": 400, \"reason\": \"Servis hatası: yanıt yok\"}"));
        }
    }

    @Test
    void theServicesStatusWithoutAnAnswerIs504Or502() {
        ResponseProfile backendStatus = parse("""
                {"success": {"httpStatus": "${backend.status}", "body": "${backend.body}"},
                 "error": {"httpStatus": "${backend.status}", "body": {"status": "${backend.status}"}}}
                """);

        assertThat(render(backendStatus, 201, "{}", Outcome.SUCCESS).response().status()).isEqualTo(201);
        assertThat(render(backendStatus, null, null, Outcome.TIMEOUT).response().status()).isEqualTo(504);
        assertThat(render(backendStatus, null, null, Outcome.TECHNICAL_ERROR).response().status()).isEqualTo(502);
        assertThat(render(backendStatus, null, null, Outcome.TIMEOUT).response().body())
                .isEqualTo(Json.parse("{\"status\": null}"));
    }

    @Test
    void headersArrayIndexesAndTheOtherOperators() {
        ResponseProfile operators = parse("""
                {"success": {
                   "cases": [
                     {"when": [{"value": "${backend.body.items.0.state}", "in": ["OPEN", "HELD"]},
                               {"value": "${backend.header.X-Trace}", "notEquals": "t-0"}],
                      "httpStatus": 202, "body": {"first": "${backend.body.items.0}", "trace": "${backend.header.x-trace}",
                                                  "missing": "${backend.header.X-None}", "text": "id ${backend.body.nope}!"}}],
                   "httpStatus": 200, "body": {"data": "${backend.body}"}},
                 "error": {"httpStatus": 500, "body": {}}}
                """);

        ResponseProfile.Rendered open = render(operators, 200, "{\"items\": [{\"state\": \"OPEN\"}]}", Outcome.SUCCESS);
        assertThat(open.matchedCase()).isZero();
        assertThat(open.response().status()).isEqualTo(202);
        assertThat(open.response().body()).isEqualTo(Json.parse("""
                {"first": {"state": "OPEN"}, "trace": "t-9", "missing": null, "text": "id !"}
                """));
        assertThat(render(operators, 200, "{\"items\": [{\"state\": \"DONE\"}]}", Outcome.SUCCESS).matchedCase()).isNull();
        assertThat(render(operators, 200, "{\"items\": []}", Outcome.SUCCESS).matchedCase()).isNull();
    }

    @Test
    void mistakesAreRejectedWithTheirFormPath() {
        ResponseProfile.Parsed parsed = ResponseProfile.parse(Json.parse("""
                {"exposeServerErrorBody": "yes",
                 "success": {
                   "cases": [
                     {"when": [{"value": "${backend.body.a..b}", "exists": true},
                               {"value": "${backend.status}", "greaterThan": 400},
                               {"value": "${request.id}", "equals": 1},
                               {"value": "sabit", "equals": 1},
                               {"value": "${backend.status}", "equals": 1, "in": [1]},
                               {"value": "${backend.status}", "exists": "evet"}],
                      "httpStatus": "200", "body": {"x": "${backend.header.}"}},
                     {"when": [], "httpStatus": 200, "body": {}}],
                   "httpStatus": 200, "body": {"y": "${backend.body.}", "z": "${backend.durationMs}"}},
                 "error": {"httpStatus": "${backend.body}", "body": {}}}
                """));

        assertThat(parsed.valid()).isFalse();
        assertThat(parsed.errors()).extracting(FieldError::field).containsExactlyInAnyOrder(
                "exposeServerErrorBody",
                "success.cases[0].when[0].value",
                "success.cases[0].when[1].greaterThan",
                "success.cases[0].when[1]",
                "success.cases[0].when[2].value",
                "success.cases[0].when[3].value",
                "success.cases[0].when[4]",
                "success.cases[0].when[5].exists",
                "success.cases[0].httpStatus",
                "success.cases[0].body.x",
                "success.cases[1].when",
                "success.body.y",
                "success.body.z",
                "error.httpStatus");
    }

    @Test
    void existingProfilesRenderAsBefore() {
        JsonNode definition = Json.parse(com.acme.yapi.core.testing.CoreFixtures.LOG_PROJECT_PROFILE);
        ResponseProfile logProject = ResponseProfile.parse(definition).profile();

        ResponseProfile.Rendered ok = render(logProject, 201, "{\"id\": 1}", Outcome.SUCCESS);
        assertThat(ok.matchedCase()).isNull();
        assertThat(ok.response().body()).isEqualTo(Json.parse("{\"BACKEND_STATUS\": 201, \"status\": 200, \"data\": {\"id\": 1}}"));
        assertThat(render(logProject, 500, "{}", Outcome.TECHNICAL_ERROR).response().body())
                .isEqualTo(Json.parse("{\"status\": 400, \"reason\": \"LogProject Hata!\"}"));
    }
}
