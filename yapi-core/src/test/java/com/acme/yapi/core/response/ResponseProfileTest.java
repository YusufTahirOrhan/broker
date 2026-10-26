package com.acme.yapi.core.response;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.validation.FieldError;

import tools.jackson.databind.JsonNode;

class ResponseProfileTest {

    static final String LOG_PROJECT = """
            {
              "success": { "httpStatus": 200,
                           "body": { "BACKEND_STATUS": "${backend.status}", "status": 200, "data": "${backend.body}" } },
              "error":   { "httpStatus": 400,
                           "body": { "status": 400, "reason": "LogProject Hata!", "detail": "${backend.body}",
                                     "code": "${backend.status}", "outcome": "${outcome}", "id": "${requestId}" } }
            }
            """;

    private final ResponseProfile profile = ResponseProfile.parse(Json.parse(LOG_PROJECT)).profile();

    @Test
    void successWrapsTheBackendBodyAndKeepsTypes() {
        ClientResponse response = profile.render(new ResponseProfile.BackendResult(201,
                Json.parse("{\"id\": 5}"), Outcome.SUCCESS, "r1"));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(Json.parse("""
                {"BACKEND_STATUS": 201, "status": 200, "data": {"id": 5}}
                """));
    }

    @Test
    void businessErrorsShowTheBackendBody() {
        ClientResponse response = profile.render(new ResponseProfile.BackendResult(404,
                Json.parse("{\"message\": \"kayıt yok\"}"), Outcome.BUSINESS_ERROR, "r1"));
        assertThat(response.status()).isEqualTo(400);
        JsonNode body = response.body();
        assertThat(body.get("detail")).isEqualTo(Json.parse("{\"message\": \"kayıt yok\"}"));
        assertThat(body.get("code").intValue()).isEqualTo(404);
        assertThat(Json.text(body.get("outcome"))).isEqualTo("BUSINESS_ERROR");
        assertThat(Json.text(body.get("id"))).isEqualTo("r1");
    }

    @Test
    void serverErrorBodiesNeverReachTheFrontend() {
        ClientResponse response = profile.render(new ResponseProfile.BackendResult(500,
                Json.string("java.lang.NullPointerException at ..."), Outcome.TECHNICAL_ERROR, "r1"));
        assertThat(response.body().has("detail")).isFalse();
        assertThat(response.body().get("code").intValue()).isEqualTo(500);
    }

    @Test
    void timeoutHasANullStatus() {
        ClientResponse response = profile.render(new ResponseProfile.BackendResult(null, null, Outcome.TIMEOUT, "r1"));
        assertThat(response.body().get("code").isNull()).isTrue();
        assertThat(Json.text(response.body().get("outcome"))).isEqualTo("TIMEOUT");
    }

    @Test
    void emptySuccessBodyBecomesNull() {
        ClientResponse response = profile.render(new ResponseProfile.BackendResult(200, null, Outcome.SUCCESS, "r1"));
        assertThat(response.body().get("data").isNull()).isTrue();
    }

    @Test
    void invalidProfilesAreRejected() {
        ResponseProfile.Parsed parsed = ResponseProfile.parse(Json.parse("""
                {"success": {"httpStatus": 99, "body": {"x": "${request.id}", "y": "${backend.headers}"}},
                 "extra": 1}
                """));
        assertThat(parsed.valid()).isFalse();
        assertThat(parsed.errors()).extracting(FieldError::field).containsExactlyInAnyOrder(
                "extra", "success.httpStatus", "success.body.x", "success.body.y", "error");
    }
}
