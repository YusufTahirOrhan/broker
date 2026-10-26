package com.acme.yapi.core.sample;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.logging.Masker;
import com.acme.yapi.core.route.FieldType;
import com.acme.yapi.core.route.RouteDefinitionParser;
import com.acme.yapi.core.spec.OpenApiSpec;
import com.acme.yapi.core.spec.SpecProposal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Drafts a route definition from a pasted request. Every non-envelope field becomes a required field whose type is
 * read from the value; nested objects open into dotted paths. When a target operation is given, fields are mapped
 * to its path, query and body parameters by name, ignoring case and the snake_case/camelCase difference.
 */
public final class SampleTemplate {

    private SampleTemplate() {
    }

    public record Result(String maintask, String subtask, List<String> removedHeaders, ObjectNode maskedSample,
                         ArrayNode fields, ObjectNode proposal, List<String> unmatchedFields,
                         List<ObjectNode> unmatchedServiceParameters) {
    }

    public static Result draft(String pasted, OpenApiSpec.Operation operation) {
        SampleParser.Sample sample = SampleParser.parse(pasted);
        if (sample.body() == null) {
            throw new IllegalArgumentException("Yapıştırılan istekte JSON gövde bulunamadı");
        }
        JsonNode body = sample.body();
        String maintask = Json.stringField(body, "maintask");
        String subtask = Json.stringField(body, "subtask");
        ObjectNode masked = (ObjectNode) Masker.mask(body, Set.of("password"));

        Map<String, FieldType> leaves = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : Json.fields(body)) {
            if (!RouteDefinitionParser.ENVELOPE_FIELDS.contains(entry.getKey())) {
                flatten(entry.getKey(), entry.getValue(), leaves);
            }
        }
        ArrayNode fields = Json.array();
        leaves.forEach((name, type) -> {
            ObjectNode field = fields.addObject();
            field.put("name", name);
            field.put("type", type.jsonName());
            field.put("required", true);
        });

        if (operation == null) {
            return new Result(maintask, subtask, sample.removedHeaders(), masked, fields, null, List.of(), List.of());
        }
        return map(maintask, subtask, sample, masked, fields, leaves, operation);
    }

    private static Result map(String maintask, String subtask, SampleParser.Sample sample, ObjectNode masked,
                              ArrayNode fields, Map<String, FieldType> leaves, OpenApiSpec.Operation operation) {
        ObjectNode proposal = SpecProposal.propose(operation);
        ObjectNode definition = (ObjectNode) proposal.get("definition");
        ObjectNode request = (ObjectNode) definition.get("request");
        Set<String> used = new LinkedHashSet<>();
        List<ObjectNode> unmatchedService = new ArrayList<>();

        ObjectNode pathVariables = Json.object();
        ObjectNode queryParams = Json.object();
        for (JsonNode parameter : operation.parameters()) {
            String in = Json.stringField(parameter, "in");
            String name = Json.stringField(parameter, "name");
            if (name == null || !("path".equals(in) || "query".equals(in))) {
                continue;
            }
            String field = match(name, leaves.keySet());
            boolean required = "path".equals(in) || isTrue(parameter.get("required"));
            if (field == null) {
                if (required) {
                    unmatchedService.add(serviceParameter(in, name));
                }
                continue;
            }
            used.add(field);
            ("path".equals(in) ? pathVariables : queryParams).put(name, "${request." + field + "}");
        }

        ObjectNode body = (ObjectNode) request.get("body");
        if ("TEMPLATE".equals(Json.stringField(body, "mode")) && body.get("template") != null
                && body.get("template").isObject()) {
            ObjectNode template = Json.object();
            JsonNode schema = requestSchema(operation);
            for (String property : Json.fieldNames(body.get("template"))) {
                String field = match(property, leaves.keySet());
                if (field == null) {
                    if (isRequired(schema, property)) {
                        unmatchedService.add(serviceParameter("body", property));
                    }
                    continue;
                }
                used.add(field);
                template.put(property, "${request." + field + "}");
            }
            body.set("template", template);
        }

        request.set("fields", fields);
        request.remove("pathVariables");
        request.remove("queryParams");
        if (pathVariables.size() > 0) {
            request.set("pathVariables", pathVariables);
        }
        if (queryParams.size() > 0) {
            request.set("queryParams", queryParams);
        }
        List<String> unmatchedFields = new ArrayList<>();
        for (String leaf : leaves.keySet()) {
            if (!used.contains(leaf)) {
                unmatchedFields.add(leaf);
            }
        }
        return new Result(maintask, subtask, sample.removedHeaders(), masked, fields, proposal, unmatchedFields,
                unmatchedService);
    }

    private static void flatten(String path, JsonNode value, Map<String, FieldType> leaves) {
        if (value.isObject() && value.size() > 0) {
            for (Map.Entry<String, JsonNode> entry : Json.fields(value)) {
                flatten(path + "." + entry.getKey(), entry.getValue(), leaves);
            }
            return;
        }
        leaves.put(path, FieldType.infer(value));
    }

    /** Field whose name (or last segment) equals the service name, ignoring case, '_' and '-'. */
    static String match(String serviceName, Set<String> fieldNames) {
        String wanted = normalize(serviceName);
        for (String field : fieldNames) {
            if (normalize(field).equals(wanted)) {
                return field;
            }
        }
        for (String field : fieldNames) {
            String last = field.substring(field.lastIndexOf('.') + 1);
            if (normalize(last).equals(wanted)) {
                return field;
            }
        }
        return null;
    }

    static String normalize(String name) {
        return name.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
    }

    private static JsonNode requestSchema(OpenApiSpec.Operation operation) {
        JsonNode content = operation.requestBody() == null ? null : operation.requestBody().get("content");
        for (Map.Entry<String, JsonNode> media : Json.fields(content)) {
            if (media.getKey().toLowerCase(Locale.ROOT).contains("json")) {
                return media.getValue().path("schema");
            }
        }
        return Json.object();
    }

    private static boolean isRequired(JsonNode schema, String property) {
        JsonNode required = schema.get("required");
        if (required != null && required.isArray()) {
            for (JsonNode name : required) {
                if (property.equals(Json.text(name))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isTrue(JsonNode node) {
        return node != null && node.isBoolean() && node.booleanValue();
    }

    private static ObjectNode serviceParameter(String in, String name) {
        ObjectNode parameter = Json.object();
        parameter.put("in", in);
        parameter.put("name", name);
        return parameter;
    }
}
