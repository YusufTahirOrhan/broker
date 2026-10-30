package com.acme.yapi.core.testcall;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.plugin.Plugin;
import com.acme.yapi.core.request.BackendRequest;
import com.acme.yapi.core.validation.FieldError;

import tools.jackson.databind.JsonNode;

/**
 * What the test panel shows: the request sent to the service, the service's response, the response the frontend
 * would get and a reading of the result. {@code request} and {@code response} are null when the sample did not
 * match the fields and nothing was sent.
 */
public record TestCallResult(RequestView request, ResponseView response, ClientView clientResponse, String outcome,
                             String interpretation, List<FieldError> errors, List<Plugin.Applied> plugins) {

    public TestCallResult {
        plugins = plugins == null ? List.of() : List.copyOf(plugins);
    }

    public TestCallResult(RequestView request, ResponseView response, ClientView clientResponse, String outcome,
                          String interpretation, List<FieldError> errors) {
        this(request, response, clientResponse, outcome, interpretation, errors, List.of());
    }

    /** The same result, naming the plugins that were applied. */
    public TestCallResult withPlugins(java.util.Collection<Plugin.Applied> applied) {
        return new TestCallResult(request, response, clientResponse, outcome, interpretation, errors,
                List.copyOf(applied));
    }

    public record RequestView(String method, String url, Map<String, String> headers, JsonNode body) {

        public static RequestView of(BackendRequest request) {
            Map<String, String> headers = new LinkedHashMap<>();
            request.headers().forEach((name, values) -> headers.put(name, String.join(", ", values)));
            JsonNode body = Json.isMissing(request.previewBody()) ? null : request.previewBody();
            return new RequestView(request.method().name(), request.uri().toString(), headers, body);
        }
    }

    /** {@code status} is null after a timeout or a connection failure. */
    public record ResponseView(Integer status, Map<String, List<String>> headers, JsonNode body, long durationMs) {
    }

    /** @param headers what the plugins added to the frontend's response */
    public record ClientView(int status, JsonNode body, Map<String, String> headers) {

        public ClientView {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }

        public ClientView(int status, JsonNode body) {
            this(status, body, Map.of());
        }
    }
}
