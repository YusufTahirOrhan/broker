package com.acme.broker.core.route;

import java.util.List;

import com.acme.broker.core.template.JsonTemplate;

import tools.jackson.databind.JsonNode;

public record BodySpec(BodyMode mode, String contentType, JsonNode rawTemplate, JsonTemplate template,
                       List<PartSpec> parts) {

    public static final String DEFAULT_CONTENT_TYPE = "application/json";

    public static BodySpec none() {
        return new BodySpec(BodyMode.NONE, null, null, null, List.of());
    }

    public String effectiveContentType() {
        return contentType == null || contentType.isBlank() ? DEFAULT_CONTENT_TYPE : contentType;
    }
}
