package com.acme.yapi.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.route.RouteValidator;

import tools.jackson.databind.JsonNode;

/** {@code yapi-local.example.json} (the login service's routes) passes the same validation as a saved route. */
class LocalExampleTest {

    @Test
    void theExampleRoutesAreValid() throws Exception {
        JsonNode example = Json.parse(Files.readString(Path.of("yapi-local.example.json")));
        RouteValidator.Context readable = RouteValidator.Context.of(List.of("Accept-Language", "X-Clientip",
                "X-User-Agent"));

        assertThat(example.get("routes")).hasSize(2);
        for (JsonNode route : example.get("routes")) {
            assertThat(RouteValidator.validate(Json.stringField(route, "httpMethod"),
                    Json.stringField(route, "pathTemplate"), route.get("definition"), readable))
                    .as(Json.stringField(route, "subtask")).isEmpty();
            assertThat(route.get("anonymous").booleanValue()).isTrue();
        }
    }
}
