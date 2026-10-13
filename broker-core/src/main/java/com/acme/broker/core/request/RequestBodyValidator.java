package com.acme.broker.core.request;

import java.util.ArrayList;
import java.util.List;

import com.acme.broker.core.json.Json;
import com.acme.broker.core.route.FieldSpec;
import com.acme.broker.core.validation.FieldError;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Checks the frontend body against the route's {@code fields}: required fields must be present, values must have
 * the declared type, absent optional fields take their default. Fields that are not defined are ignored; this is a
 * contract check, not business validation.
 */
public final class RequestBodyValidator {

    private RequestBodyValidator() {
    }

    /** {@code effectiveBody} is a copy of the body with defaults filled in. */
    public record Result(ObjectNode effectiveBody, List<FieldError> errors) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    public static Result validate(ObjectNode body, List<FieldSpec> fields) {
        ObjectNode effective = body.deepCopy();
        List<FieldError> errors = new ArrayList<>();
        for (FieldSpec field : fields) {
            JsonNode value = find(effective, field.name());
            if (Json.isAbsent(value)) {
                if (field.required()) {
                    errors.add(new FieldError(field.name(), "Zorunlu alan eksik"));
                } else if (field.hasDefault()) {
                    put(effective, field.name(), field.defaultValue().deepCopy());
                }
                continue;
            }
            if (!field.type().matches(value)) {
                errors.add(new FieldError(field.name(), field.type().jsonName() + " bekleniyor"));
            }
        }
        return new Result(effective, errors);
    }

    /** Value at a dotted path; missing when any step is absent or not an object. */
    public static JsonNode find(JsonNode root, String dottedPath) {
        JsonNode current = root;
        for (String segment : dottedPath.split("\\.")) {
            if (current == null || !current.isObject()) {
                return Json.missing();
            }
            current = current.get(segment);
        }
        return current == null ? Json.missing() : current;
    }

    private static void put(ObjectNode root, String dottedPath, JsonNode value) {
        String[] segments = dottedPath.split("\\.");
        ObjectNode current = root;
        for (int i = 0; i < segments.length - 1; i++) {
            JsonNode next = current.get(segments[i]);
            if (next == null || next.isNull()) {
                ObjectNode created = Json.object();
                current.set(segments[i], created);
                current = created;
            } else if (next.isObject()) {
                current = (ObjectNode) next;
            } else {
                return; // the frontend sent a scalar where an object was expected; leave its value alone
            }
        }
        current.set(segments[segments.length - 1], value);
    }
}
