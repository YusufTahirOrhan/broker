package com.acme.broker.core.testcall;

import java.util.Map;

import tools.jackson.databind.JsonNode;

/**
 * A test call with an unsaved definition. The central admin sends it to the environment Admin API, which fills in
 * {@code baseUrl} from the runtime DB and forwards it to the broker's internal endpoint; the broker runs it through
 * the same validator and request builder as real traffic. Test calls are not written to Kafka.
 *
 * @param responseProfile the profile to wrap the response with, or null
 * @param sample          the frontend body, envelope fields included or not
 * @param headers         frontend request headers that {@code ${header.*}} may read
 * @param userId          the user {@code ${user.id}} and {@code X-uid} stand for
 * @param permission      what {@code ${user.permission}} stands for, or null
 */
public record TestCallRequest(String maintask, String baseUrl, String httpMethod, String pathTemplate,
                              JsonNode definition, Profile responseProfile, JsonNode sample,
                              Map<String, String> headers, String userId, String permission, String actor) {

    /** Path of the broker's internal endpoint; it exists only where test calls are enabled. */
    public static final String PATH = "/internal/v1/test";
    /** Shared secret between the Admin API and the broker's internal endpoint. */
    public static final String SECRET_HEADER = "X-Broker-Test-Secret";

    public record Profile(String name, Integer version, JsonNode definition) {
    }

    public TestCallRequest withBaseUrl(String url) {
        return new TestCallRequest(maintask, url, httpMethod, pathTemplate, definition, responseProfile, sample,
                headers, userId, permission, actor);
    }
}
