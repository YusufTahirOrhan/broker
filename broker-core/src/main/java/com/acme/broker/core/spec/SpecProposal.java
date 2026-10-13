package com.acme.broker.core.spec;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.acme.broker.core.json.Json;
import com.acme.broker.core.route.FieldType;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Proposes a route definition from an OpenAPI operation: every service parameter and body property becomes a
 * frontend field with the same name and is mapped one to one. DevOps then only renames fields to what the frontend
 * sends and sets defaults.
 */
public final class SpecProposal {

    private SpecProposal() {
    }

    /** {@code {httpMethod, pathTemplate, definition}} for a new subtask; the subtask name is left to DevOps. */
    public static ObjectNode propose(OpenApiSpec.Operation operation) {
        Map<String, ObjectNode> fields = new LinkedHashMap<>();
        ObjectNode pathVariables = Json.object();
        ObjectNode queryParams = Json.object();

        for (JsonNode parameter : operation.parameters()) {
            String in = Json.stringField(parameter, "in");
            String name = Json.stringField(parameter, "name");
            if (name == null || in == null) {
                continue;
            }
            boolean required = "path".equals(in) || isTrue(parameter.get("required"));
            switch (in) {
                case "path" -> {
                    addField(fields, name, typeOf(parameter.get("schema")), true);
                    pathVariables.put(name, "${request." + name + "}");
                }
                case "query" -> {
                    addField(fields, name, typeOf(parameter.get("schema")), required);
                    queryParams.put(name, "${request." + name + "}");
                }
                default -> {
                    // header and cookie parameters are set by the broker or the definition, not by the frontend
                }
            }
        }

        ObjectNode body = proposeBody(operation, fields);

        ObjectNode request = Json.object();
        ArrayNode fieldArray = request.putArray("fields");
        fields.values().forEach(fieldArray::add);
        if (pathVariables.size() > 0) {
            request.set("pathVariables", pathVariables);
        }
        if (queryParams.size() > 0) {
            request.set("queryParams", queryParams);
        }
        request.set("body", body);

        ObjectNode definition = Json.object();
        definition.set("request", request);
        definition.putObject("response").put("type", responseType(operation));
        definition.put("timeoutMs", 5000);

        ObjectNode proposal = Json.object();
        proposal.put("httpMethod", operation.httpMethod());
        proposal.put("pathTemplate", operation.path());
        proposal.set("definition", definition);
        return proposal;
    }

    private static ObjectNode proposeBody(OpenApiSpec.Operation operation, Map<String, ObjectNode> fields) {
        ObjectNode body = Json.object();
        JsonNode content = operation.requestBody() == null ? null : operation.requestBody().get("content");
        if (content == null || !content.isObject() || content.size() == 0
                || "GET".equals(operation.httpMethod()) || "DELETE".equals(operation.httpMethod())) {
            body.put("mode", "NONE");
            return body;
        }
        boolean bodyRequired = isTrue(operation.requestBody().get("required"));
        String mediaType = pickMediaType(content);
        JsonNode schema = content.path(mediaType).path("schema");
        String lower = mediaType.toLowerCase(Locale.ROOT);

        if (lower.startsWith("multipart/form-data")) {
            body.put("mode", "MULTIPART");
            ArrayNode parts = body.putArray("parts");
            for (Map.Entry<String, JsonNode> property : Json.fields(schema.path("properties"))) {
                boolean file = "binary".equals(Json.stringField(property.getValue(), "format"));
                boolean required = isRequired(schema, property.getKey());
                addField(fields, property.getKey(), file ? FieldType.OBJECT : typeOf(property.getValue()), required);
                ObjectNode part = parts.addObject();
                part.put("name", property.getKey());
                part.put("value", "${request." + property.getKey() + "}");
                if (file) {
                    part.put("file", true);
                }
            }
            return body;
        }

        body.put("mode", "TEMPLATE");
        if (!lower.contains("json")) {
            body.put("contentType", mediaType);
        }
        JsonNode properties = schema.path("properties");
        if ("object".equals(Json.stringField(schema, "type")) || properties.isObject()) {
            ObjectNode template = body.putObject("template");
            for (Map.Entry<String, JsonNode> property : Json.fields(properties)) {
                addField(fields, property.getKey(), typeOf(property.getValue()),
                        bodyRequired && isRequired(schema, property.getKey()));
                template.put(property.getKey(), "${request." + property.getKey() + "}");
            }
            return body;
        }
        addField(fields, "payload", typeOf(schema), bodyRequired);
        body.put("template", "${request.payload}");
        return body;
    }

    private static boolean isTrue(JsonNode node) {
        return node != null && node.isBoolean() && node.booleanValue();
    }

    private static String pickMediaType(JsonNode content) {
        String first = null;
        for (String type : Json.fieldNames(content)) {
            if (first == null) {
                first = type;
            }
            if (type.toLowerCase(Locale.ROOT).contains("json")) {
                return type;
            }
        }
        return first;
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

    private static void addField(Map<String, ObjectNode> fields, String name, FieldType type, boolean required) {
        if (fields.containsKey(name)) {
            if (required) {
                fields.get(name).put("required", true);
            }
            return;
        }
        ObjectNode field = Json.object();
        field.put("name", name);
        field.put("type", type.jsonName());
        field.put("required", required);
        fields.put(name, field);
    }

    static FieldType typeOf(JsonNode schema) {
        if (schema == null || !schema.isObject()) {
            return FieldType.STRING;
        }
        String type = Json.stringField(schema, "type");
        if (type == null && schema.has("properties")) {
            return FieldType.OBJECT;
        }
        if (type == null) {
            return FieldType.STRING;
        }
        return switch (type) {
            case "integer" -> FieldType.INTEGER;
            case "number" -> FieldType.NUMBER;
            case "boolean" -> FieldType.BOOLEAN;
            case "object" -> FieldType.OBJECT;
            case "array" -> FieldType.ARRAY;
            default -> FieldType.STRING;
        };
    }

    static String responseType(OpenApiSpec.Operation operation) {
        boolean json = false;
        boolean text = false;
        for (Map.Entry<String, JsonNode> response : Json.fields(operation.responses())) {
            if (!response.getKey().startsWith("2")) {
                continue;
            }
            for (Map.Entry<String, JsonNode> media : Json.fields(response.getValue().get("content"))) {
                String type = media.getKey().toLowerCase(Locale.ROOT);
                String format = Json.stringField(media.getValue().path("schema"), "format");
                if (type.contains("octet-stream") || type.startsWith("application/pdf") || type.startsWith("image/")
                        || type.startsWith("application/vnd.") || "binary".equals(format)) {
                    return "BINARY";
                }
                if (type.contains("json")) {
                    json = true;
                } else if (type.startsWith("text/")) {
                    text = true;
                }
            }
        }
        return !json && text ? "TEXT" : "JSON";
    }
}
