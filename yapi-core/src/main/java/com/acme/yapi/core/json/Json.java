package com.acme.yapi.core.json;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.MissingNode;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Single place where the Jackson tree API is used directly. Every other class goes through
 * these helpers so that a Jackson upgrade touches one file.
 */
public final class Json {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private Json() {
    }

    public static JsonMapper mapper() {
        return MAPPER;
    }

    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    public static ArrayNode array() {
        return MAPPER.createArrayNode();
    }

    public static JsonNode string(String value) {
        return value == null ? NullNode.getInstance() : NODES.stringNode(value);
    }

    public static JsonNode number(int value) {
        return NODES.numberNode(value);
    }

    public static JsonNode number(long value) {
        return NODES.numberNode(value);
    }

    public static JsonNode number(BigDecimal value) {
        return NODES.numberNode(value);
    }

    public static JsonNode number(BigInteger value) {
        return NODES.numberNode(value);
    }

    public static JsonNode bool(boolean value) {
        return NODES.booleanNode(value);
    }

    public static JsonNode nullNode() {
        return NullNode.getInstance();
    }

    public static JsonNode missing() {
        return MissingNode.getInstance();
    }

    public static boolean isMissing(JsonNode node) {
        return node == null || node.isMissingNode();
    }

    /** Absent, JSON null or missing. */
    public static boolean isAbsent(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull();
    }

    public static boolean isString(JsonNode node) {
        return node != null && node.isString();
    }

    /**
     * A yes/no field as other systems send it: {@code true}, {@code 1}, {@code "true"} or {@code "1"} is yes, anything
     * else (absent, null, false, 0) is no.
     */
    public static boolean isTrue(JsonNode node) {
        if (node == null) {
            return false;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.intValue() == 1;
        }
        if (node.isString()) {
            String text = node.stringValue().trim().toLowerCase(java.util.Locale.ROOT);
            return "true".equals(text) || "1".equals(text);
        }
        return false;
    }

    /** The string value of a string node; JSON text for anything else. */
    public static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isString()) {
            return node.stringValue();
        }
        if (node.isObject() || node.isArray()) {
            return node.toString();
        }
        return node.asString();
    }

    /** String value of a field, or null when it is absent or not a string. */
    public static String stringField(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isString() ? value.stringValue() : null;
    }

    public static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        if (node != null && node.isObject()) {
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                names.add(entry.getKey());
            }
        }
        return names;
    }

    /** The values of a JSON array of strings, as stored in a text column; empty for null text or a non-array. */
    public static List<String> stringList(String text) {
        List<String> values = new ArrayList<>();
        JsonNode node = text == null ? null : parse(text);
        if (node != null && node.isArray()) {
            node.forEach(value -> values.add(text(value)));
        }
        return values;
    }

    public static Iterable<Map.Entry<String, JsonNode>> fields(JsonNode node) {
        if (node == null || !node.isObject()) {
            return List.of();
        }
        return node.properties();
    }

    public static JsonNode parse(String text) {
        return MAPPER.readTree(text);
    }

    public static JsonNode parse(byte[] bytes) {
        return MAPPER.readTree(bytes);
    }

    /** Parses JSON, returning null instead of throwing when the input is not valid JSON. */
    public static JsonNode tryParse(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            return MAPPER.readTree(bytes);
        } catch (JacksonException e) {
            return null;
        }
    }

    public static JsonNode tryParse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return tryParse(text.getBytes(StandardCharsets.UTF_8));
    }

    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    public static byte[] writeBytes(Object value) {
        return MAPPER.writeValueAsBytes(value);
    }

    public static <T> T convert(JsonNode node, Class<T> type) {
        return MAPPER.treeToValue(node, type);
    }

    public static JsonNode toTree(Object value) {
        return MAPPER.valueToTree(value);
    }

    /**
     * Plain Java objects (LinkedHashMap, ArrayList, Integer, Long, Double, Boolean, String) as the old
     * broker saw them; {@code String.valueOf} of the result reproduces its {@code toString} output.
     */
    public static Object toJava(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return MAPPER.treeToValue(node, Object.class);
    }

    /** Canonical form with object keys sorted at every level; used for fingerprints and comparisons. */
    public static JsonNode canonical(JsonNode node) {
        if (node == null) {
            return nullNode();
        }
        if (node.isObject()) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                sorted.put(entry.getKey(), canonical(entry.getValue()));
            }
            ObjectNode result = object();
            sorted.forEach(result::set);
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = array();
            for (JsonNode element : node) {
                result.add(canonical(element));
            }
            return result;
        }
        return node;
    }

    public static int utf8Length(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }
}
