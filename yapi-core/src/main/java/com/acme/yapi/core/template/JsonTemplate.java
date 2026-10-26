package com.acme.yapi.core.template;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.acme.yapi.core.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A JSON value compiled once (when a route or profile is loaded) and filled on every request. Object keys are
 * literal; string values may hold placeholders. A value without a value is left out of its object or array.
 */
public sealed interface JsonTemplate {

    JsonNode render(Resolver resolver, boolean keepNulls);

    void collectPlaceholders(String path, List<Located> into);

    /** A placeholder together with where it appears, for error messages. */
    record Located(String path, Placeholder placeholder, boolean single) {
    }

    static JsonTemplate compile(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return new Constant(Json.missing());
        }
        if (node.isObject()) {
            Map<String, JsonTemplate> fields = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> entry : Json.fields(node)) {
                fields.put(entry.getKey(), compile(entry.getValue()));
            }
            return new ObjectTemplate(fields);
        }
        if (node.isArray()) {
            List<JsonTemplate> elements = new ArrayList<>();
            for (JsonNode element : node) {
                elements.add(compile(element));
            }
            return new ArrayTemplate(elements);
        }
        if (node.isString()) {
            TemplateString template = TemplateString.parse(node.stringValue());
            return template.isPlain() ? new Constant(node) : new StringTemplate(template);
        }
        return new Constant(node);
    }

    default List<Located> placeholders() {
        List<Located> result = new ArrayList<>();
        collectPlaceholders("", result);
        return result;
    }

    record Constant(JsonNode value) implements JsonTemplate {
        @Override
        public JsonNode render(Resolver resolver, boolean keepNulls) {
            return value;
        }

        @Override
        public void collectPlaceholders(String path, List<Located> into) {
        }
    }

    record StringTemplate(TemplateString template) implements JsonTemplate {
        @Override
        public JsonNode render(Resolver resolver, boolean keepNulls) {
            JsonNode value = template.render(resolver, keepNulls);
            return value.isObject() || value.isArray() ? value.deepCopy() : value;
        }

        @Override
        public void collectPlaceholders(String path, List<Located> into) {
            for (Placeholder placeholder : template.placeholders()) {
                into.add(new Located(path, placeholder, template.isSinglePlaceholder()));
            }
        }
    }

    record ObjectTemplate(Map<String, JsonTemplate> fields) implements JsonTemplate {
        @Override
        public JsonNode render(Resolver resolver, boolean keepNulls) {
            ObjectNode result = Json.object();
            for (Map.Entry<String, JsonTemplate> entry : fields.entrySet()) {
                JsonNode value = entry.getValue().render(resolver, keepNulls);
                if (!Json.isMissing(value)) {
                    result.set(entry.getKey(), value);
                }
            }
            return result;
        }

        @Override
        public void collectPlaceholders(String path, List<Located> into) {
            fields.forEach((key, value) -> value.collectPlaceholders(path.isEmpty() ? key : path + "." + key, into));
        }
    }

    record ArrayTemplate(List<JsonTemplate> elements) implements JsonTemplate {
        @Override
        public JsonNode render(Resolver resolver, boolean keepNulls) {
            ArrayNode result = Json.array();
            for (JsonTemplate element : elements) {
                JsonNode value = element.render(resolver, keepNulls);
                if (!Json.isMissing(value)) {
                    result.add(value);
                }
            }
            return result;
        }

        @Override
        public void collectPlaceholders(String path, List<Located> into) {
            for (int i = 0; i < elements.size(); i++) {
                elements.get(i).collectPlaceholders(path + "[" + i + "]", into);
            }
        }
    }
}
