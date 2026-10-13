package com.acme.broker.core.request;

import java.net.URI;
import java.util.List;
import java.util.Map;

import com.acme.broker.core.route.HttpMethodName;

import tools.jackson.databind.JsonNode;

/**
 * The request the broker sends to the backend. {@code previewBody} is what admin screens show: the JSON body, the
 * text body as a string, or a summary of multipart parts.
 */
public record BackendRequest(HttpMethodName method, URI uri, Map<String, List<String>> headers, byte[] body,
                             String contentType, JsonNode previewBody) {

    public boolean hasBody() {
        return body != null;
    }
}
