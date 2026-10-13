package com.acme.broker.core.response;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.acme.broker.core.json.Json;
import com.acme.broker.core.template.JsonTemplate;
import com.acme.broker.core.template.Placeholder;
import com.acme.broker.core.template.Resolver;
import com.acme.broker.core.template.TemplateString;
import com.acme.broker.core.validation.FieldError;

import tools.jackson.databind.JsonNode;

/**
 * A compiled response profile: the envelope a project's frontend expects around backend responses.
 *
 * <pre>
 * {"success": {"httpStatus": 200, "body": {"BACKEND_STATUS": "${backend.status}", "data": "${backend.body}"}},
 *  "error":   {"httpStatus": 400, "body": {"status": 400, "reason": "LogProject Hata!"}}}
 * </pre>
 *
 * The success template is used for 2xx responses; the error template for 4xx, 5xx, timeouts and connection
 * failures. A 5xx body is never handed to the template, so stack traces do not leak to the frontend.
 */
public final class ResponseProfile {

    private static final Set<String> ROOT_KEYS = Set.of("success", "error");
    private static final Set<String> TEMPLATE_KEYS = Set.of("httpStatus", "body");

    private final Template success;
    private final Template error;

    private ResponseProfile(Template success, Template error) {
        this.success = success;
        this.error = error;
    }

    private record Template(int httpStatus, JsonTemplate body) {
    }

    public record Parsed(ResponseProfile profile, List<FieldError> errors) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    /** What the profile renders from: the backend's status and body, or null status after a timeout. */
    public record BackendResult(Integer status, JsonNode body, Outcome outcome, String requestId) {
    }

    public static Parsed parse(JsonNode definition) {
        List<FieldError> errors = new ArrayList<>();
        if (definition == null || !definition.isObject()) {
            errors.add(new FieldError("", "Profil bir JSON nesnesi olmalı"));
            return new Parsed(null, errors);
        }
        for (String key : Json.fieldNames(definition)) {
            if (!ROOT_KEYS.contains(key)) {
                errors.add(new FieldError(key, "Bilinmeyen alan"));
            }
        }
        Template success = parseTemplate(definition.get("success"), "success", errors);
        Template error = parseTemplate(definition.get("error"), "error", errors);
        return new Parsed(errors.isEmpty() ? new ResponseProfile(success, error) : null, errors);
    }

    private static Template parseTemplate(JsonNode node, String path, List<FieldError> errors) {
        if (node == null || !node.isObject()) {
            errors.add(new FieldError(path, "Zorunlu; httpStatus ve body içeren bir nesne olmalı"));
            return null;
        }
        for (String key : Json.fieldNames(node)) {
            if (!TEMPLATE_KEYS.contains(key)) {
                errors.add(new FieldError(path + "." + key, "Bilinmeyen alan"));
            }
        }
        JsonNode status = node.get("httpStatus");
        int httpStatus = 0;
        if (status == null || !status.isIntegralNumber() || status.intValue() < 100 || status.intValue() > 599) {
            errors.add(new FieldError(path + ".httpStatus", "100 ile 599 arasında bir HTTP status olmalı"));
        } else {
            httpStatus = status.intValue();
        }
        JsonNode body = node.get("body");
        if (body == null) {
            errors.add(new FieldError(path + ".body", "Zorunlu"));
            return null;
        }
        JsonTemplate template = JsonTemplate.compile(body);
        for (JsonTemplate.Located located : template.placeholders()) {
            String where = located.path().isEmpty() ? path + ".body" : path + ".body." + located.path();
            if (!isProfilePlaceholder(located.placeholder())) {
                errors.add(new FieldError(where, "Profilde kullanılamayan yer tutucu: " + located.placeholder()));
            }
        }
        checkUnclosed(body, path + ".body", errors);
        return new Template(httpStatus, template);
    }

    private static boolean isProfilePlaceholder(Placeholder placeholder) {
        return switch (placeholder.root()) {
            case Placeholder.BACKEND -> "status".equals(placeholder.path()) || "body".equals(placeholder.path());
            case Placeholder.OUTCOME, Placeholder.REQUEST_ID -> placeholder.path().isEmpty();
            default -> false;
        };
    }

    private static void checkUnclosed(JsonNode node, String path, List<FieldError> errors) {
        if (node.isString()) {
            if (TemplateString.parse(node.stringValue()).hasUnclosedPlaceholder()) {
                errors.add(new FieldError(path, "Kapanmamış yer tutucu: " + node.stringValue()));
            }
        } else if (node.isObject()) {
            for (Map.Entry<String, JsonNode> entry : Json.fields(node)) {
                checkUnclosed(entry.getValue(), path + "." + entry.getKey(), errors);
            }
        } else if (node.isArray()) {
            int index = 0;
            for (JsonNode element : node) {
                checkUnclosed(element, path + "[" + index++ + "]", errors);
            }
        }
    }

    /** Wraps a backend result; 2xx uses the success template, everything else the error template. */
    public ClientResponse render(BackendResult result) {
        boolean ok = result.outcome() == Outcome.SUCCESS;
        Template template = ok ? success : error;
        boolean bodyAllowed = ok || (result.status() != null && result.status() >= 400 && result.status() < 500);
        Resolver resolver = placeholder -> switch (placeholder.root()) {
            case Placeholder.BACKEND -> "status".equals(placeholder.path())
                    ? (result.status() == null ? Json.nullNode() : Json.number(result.status()))
                    : bodyAllowed ? valueOrNull(result.body()) : Json.missing();
            case Placeholder.OUTCOME -> Json.string(result.outcome().name());
            case Placeholder.REQUEST_ID -> Json.string(result.requestId());
            default -> Json.missing();
        };
        JsonNode body = template.body().render(resolver, true);
        return new ClientResponse(template.httpStatus(), Json.isMissing(body) ? Json.nullNode() : body);
    }

    private static JsonNode valueOrNull(JsonNode body) {
        return Json.isMissing(body) ? Json.nullNode() : body;
    }
}
