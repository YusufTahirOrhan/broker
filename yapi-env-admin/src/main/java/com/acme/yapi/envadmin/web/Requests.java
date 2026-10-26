package com.acme.yapi.envadmin.web;

import java.util.List;
import java.util.Map;

import com.acme.yapi.core.route.AlarmRoute;

import tools.jackson.databind.JsonNode;

/** Request bodies of the Admin API, as the central admin sends them. */
public final class Requests {

    private Requests() {
    }

    /** {@code PUT /admin/v1/routes/{maintask}/{subtask}}: a route version to make active. */
    public record RouteApply(Long promotionId, Long definitionId, Integer version, String httpMethod,
                             String pathTemplate, List<String> permissions, Boolean anonymous,
                             String responseProfile, JsonNode definition, String actor) {
    }

    /** {@code PUT /admin/v1/response-profiles/{name}}. */
    public record ProfileApply(Long promotionId, Long profileId, Integer version, JsonNode definition, String actor) {
    }

    /** {@code POST /admin/v1/routes/{maintask}/{subtask}/disable}. */
    public record Disable(Long promotionId, String actor) {
    }

    /** {@code PUT /admin/v1/projects/{maintask}}; {@code alarmRoute} null clears it. */
    public record ProjectApply(String baseUrl, Boolean enabled, AlarmRoute alarmRoute, String actor) {
    }

    /** {@code POST /admin/v1/probe}; path variables without a value are filled with a sample. */
    public record Probe(String maintask, String httpMethod, String pathTemplate, Map<String, String> pathVariables,
                        Long promotionId, String actor) {
    }
}
