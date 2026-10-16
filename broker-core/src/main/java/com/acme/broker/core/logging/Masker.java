package com.acme.broker.core.logging;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.acme.broker.core.json.Json;
import com.acme.broker.core.naming.Names;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Masks sensitive fields at every depth before anything reaches Kafka. Field names match case-insensitively. */
public final class Masker {

    public static final String MASK = "xxxx";

    private Masker() {
    }

    public static JsonNode mask(JsonNode node, Collection<String> fieldNames) {
        if (node == null) {
            return null;
        }
        Set<String> keys = fieldNames.stream().map(Names::key).collect(Collectors.toSet());
        return keys.isEmpty() ? node.deepCopy() : maskNode(node, keys);
    }

    private static JsonNode maskNode(JsonNode node, Set<String> keys) {
        if (node.isObject()) {
            ObjectNode result = Json.object();
            for (Map.Entry<String, JsonNode> entry : Json.fields(node)) {
                if (keys.contains(Names.key(entry.getKey())) && !entry.getValue().isNull()) {
                    result.put(entry.getKey(), MASK);
                } else {
                    result.set(entry.getKey(), maskNode(entry.getValue(), keys));
                }
            }
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = Json.array();
            for (JsonNode element : node) {
                result.add(maskNode(element, keys));
            }
            return result;
        }
        return node;
    }

    /** A logged body: the JSON value itself, or its text cut at {@code maxBytes} when it is larger. */
    public record Capture(JsonNode body, int size, boolean truncated) {
    }

    public static Capture capture(JsonNode body, int maxBytes) {
        if (body == null || body.isMissingNode()) {
            return new Capture(null, 0, false);
        }
        String text = body.isString() ? body.stringValue() : body.toString();
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return new Capture(body, bytes.length, false);
        }
        return new Capture(Json.string(cut(bytes, maxBytes)), bytes.length, true);
    }

    /**
     * The value as a string node: a string as it is, anything else as its JSON text; cut at {@code maxBytes} UTF-8
     * bytes without splitting a character when it is longer.
     */
    public static Capture text(JsonNode value, int maxBytes) {
        String text = value == null || value.isMissingNode() ? "null" : value.isString() ? value.stringValue()
                : value.toString();
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return new Capture(Json.string(text), bytes.length, false);
        }
        return new Capture(Json.string(cut(bytes, maxBytes)), bytes.length, true);
    }

    /** Cuts UTF-8 bytes without splitting a multi-byte character. */
    static String cut(byte[] bytes, int maxBytes) {
        int end = maxBytes;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }
}
