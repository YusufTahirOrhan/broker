package com.acme.broker.admin.route;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.acme.broker.core.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * History rows hold {@code to_jsonb(route_definition)}: snake_case columns. Screens, comparisons and promotions use
 * the camelCase view of what a version is, without bookkeeping columns.
 */
public final class RouteSnapshots {

    private RouteSnapshots() {
    }

    public static ObjectNode view(JsonNode stored) {
        ObjectNode view = Json.object();
        view.set("subtask", stored.get("subtask"));
        view.set("httpMethod", stored.get("http_method"));
        view.set("pathTemplate", stored.get("path_template"));
        view.set("permissions", array(stored.get("permissions")));
        view.set("foreignPermissions", array(stored.get("foreign_permissions")));
        view.set("anonymous", stored.get("anonymous"));
        view.set("responseProfileId", stored.has("response_profile_id") ? stored.get("response_profile_id") : Json.nullNode());
        view.set("definition", stored.get("definition"));
        view.set("status", stored.get("status"));
        return view;
    }

    public static ObjectNode view(RouteRepository.Row row) {
        ObjectNode view = Json.object();
        view.put("subtask", row.subtask());
        view.put("httpMethod", row.httpMethod());
        view.put("pathTemplate", row.pathTemplate());
        ArrayNode permissions = view.putArray("permissions");
        row.permissions().forEach(permissions::add);
        ArrayNode foreign = view.putArray("foreignPermissions");
        row.foreignPermissions().forEach(foreign::add);
        view.put("anonymous", row.anonymous());
        if (row.responseProfileId() == null) {
            view.putNull("responseProfileId");
        } else {
            view.put("responseProfileId", row.responseProfileId());
        }
        view.set("definition", row.definition());
        view.put("status", row.status());
        return view;
    }

    public static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(value -> values.add(Json.text(value)));
        }
        return values;
    }

    /** Foreign permissions in {@code after} that {@code before} did not have. */
    public static List<String> foreignAdded(JsonNode before, JsonNode after) {
        Set<String> added = new LinkedHashSet<>(strings(after == null ? null : after.get("foreignPermissions")));
        added.removeAll(strings(before == null ? null : before.get("foreignPermissions")));
        return List.copyOf(added);
    }

    public static boolean opensAnonymous(JsonNode before, JsonNode after) {
        boolean wasAnonymous = before != null && before.path("anonymous").booleanValue();
        return after != null && after.path("anonymous").booleanValue() && !wasAnonymous;
    }

    private static JsonNode array(JsonNode node) {
        return node == null || node.isNull() ? Json.array() : node;
    }
}
