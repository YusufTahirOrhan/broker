package com.acme.yapi.core.alarm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.naming.Names;
import com.acme.yapi.core.route.RouteDefinitionParser;

import tools.jackson.databind.JsonNode;

/**
 * What the alarm check sends and reads. The values are every leaf of the request body, nested ones too, as text and
 * in the body's order; the envelope fields ({@code maintask}, {@code subtask}, {@code application}, {@code uid}) and
 * the fields the route masks in its logs are left out, and so are JSON nulls.
 */
public final class AlarmValues {

    private AlarmValues() {
    }

    public static List<String> of(JsonNode body, Collection<String> maskedFields) {
        Set<String> masked = maskedFields.stream().map(Names::key).collect(Collectors.toSet());
        List<String> values = new ArrayList<>();
        if (body != null && body.isObject()) {
            for (Map.Entry<String, JsonNode> field : Json.fields(body)) {
                if (!RouteDefinitionParser.ENVELOPE_FIELDS.contains(field.getKey())) {
                    collect(field.getKey(), field.getValue(), masked, values);
                }
            }
        }
        return values;
    }

    private static void collect(String name, JsonNode node, Set<String> masked, List<String> values) {
        if (name != null && masked.contains(Names.key(name))) {
            return;
        }
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : Json.fields(node)) {
                collect(field.getKey(), field.getValue(), masked, values);
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                collect(null, element, masked, values);
            }
        } else if (!node.isNull() && !node.isMissingNode()) {
            values.add(Json.text(node));
        }
    }

    /**
     * The values that raised an alarm, from the alarm service's answer: the list at {@code resultPath} (dotted; null
     * for the answer itself). Empty when the answer has no such list, which the caller treats as the service's error.
     */
    public static Optional<List<String>> alarms(JsonNode answer, String resultPath) {
        JsonNode list = answer;
        if (resultPath != null) {
            for (String step : resultPath.split("\\.")) {
                list = list == null || !list.isObject() ? null : list.get(step);
            }
        }
        if (list == null || !list.isArray()) {
            return Optional.empty();
        }
        List<String> alarms = new ArrayList<>();
        for (JsonNode element : list) {
            if (!element.isNull()) {
                alarms.add(Json.text(element));
            }
        }
        return Optional.of(List.copyOf(alarms));
    }
}
