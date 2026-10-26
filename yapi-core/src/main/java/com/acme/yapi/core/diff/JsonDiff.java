package com.acme.yapi.core.diff;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import com.acme.yapi.core.json.Json;

import tools.jackson.databind.JsonNode;

/**
 * Field-level difference between two JSON documents, for version comparison and approval screens. Objects are
 * compared key by key; arrays of objects element by element; any other array as a whole value.
 */
public final class JsonDiff {

    private JsonDiff() {
    }

    /** {@code before} or {@code after} is null when the field was added or removed. */
    public record Change(String path, JsonNode before, JsonNode after) {
    }

    public static List<Change> diff(JsonNode before, JsonNode after) {
        List<Change> changes = new ArrayList<>();
        compare("", normalize(before), normalize(after), changes);
        return changes;
    }

    private static JsonNode normalize(JsonNode node) {
        return Json.isMissing(node) ? null : node;
    }

    private static void compare(String path, JsonNode before, JsonNode after, List<Change> changes) {
        if (before == null && after == null) {
            return;
        }
        if (before == null || after == null) {
            changes.add(new Change(path, before, after));
            return;
        }
        if (before.isObject() && after.isObject()) {
            TreeSet<String> keys = new TreeSet<>(Json.fieldNames(before));
            keys.addAll(Json.fieldNames(after));
            for (String key : keys) {
                compare(path.isEmpty() ? key : path + "." + key, before.get(key), after.get(key), changes);
            }
            return;
        }
        if (before.isArray() && after.isArray() && arrayOfObjects(before) && arrayOfObjects(after)) {
            int size = Math.max(before.size(), after.size());
            for (int i = 0; i < size; i++) {
                compare(path + "[" + i + "]", i < before.size() ? before.get(i) : null,
                        i < after.size() ? after.get(i) : null, changes);
            }
            return;
        }
        if (!before.equals(after)) {
            changes.add(new Change(path, before, after));
        }
    }

    private static boolean arrayOfObjects(JsonNode array) {
        if (array.size() == 0) {
            return true;
        }
        for (JsonNode element : array) {
            if (!element.isObject()) {
                return false;
            }
        }
        return true;
    }
}
