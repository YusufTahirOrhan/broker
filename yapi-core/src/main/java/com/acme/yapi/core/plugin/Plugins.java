package com.acme.yapi.core.plugin;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.naming.Names;
import com.acme.yapi.core.request.BackendRequest;
import com.acme.yapi.core.request.BackendRequestBuilder;
import com.acme.yapi.core.request.RequestScope;
import com.acme.yapi.core.route.BodyMode;
import com.acme.yapi.core.route.RequestSpec;
import com.acme.yapi.core.route.RouteDefinition;
import com.acme.yapi.core.template.ContextPlaceholders;
import com.acme.yapi.core.template.Placeholder;
import com.acme.yapi.core.template.Resolver;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Choosing and applying plugins. The same code serves the broker, the admin preview and test calls.
 *
 * <ul>
 *   <li>A subtask gets every {@code GLOBAL} plugin, the {@code PROJECT} plugins listing its project and the
 *       {@code SUBTASK} plugins its definition names in {@code plugins}, less those in {@code disabledPlugins};
 *       in that order of scope, by name within a scope.</li>
 *   <li>Request operations work on the request the definition built and leave alone what the definition wrote
 *       itself: its headers, its query parameters, the fields of its body template. The broker's own headers
 *       ({@code X-uid}, {@code X-Request-Id}, {@code Content-Type}) are kept too.</li>
 *   <li>Response operations work on what the frontend gets, after the response profile. Body operations need a
 *       JSON object and are skipped otherwise; a file response gets header operations only.</li>
 *   <li>An operation whose value has no value (a placeholder without one) is skipped.</li>
 * </ul>
 */
public final class Plugins {

    private static final Set<String> BROKER_REQUEST_HEADERS = Set.of(Names.key(BackendRequestBuilder.USER_HEADER),
            Names.key(BackendRequestBuilder.REQUEST_ID_HEADER), "content-type");

    private Plugins() {
    }

    /** The plugins of a subtask, in the order they apply. */
    public static List<Plugin> select(Collection<Plugin> all, String maintask, RouteDefinition definition) {
        Set<String> chosen = keys(definition.plugins());
        Set<String> disabled = keys(definition.disabledPlugins());
        return all.stream()
                .filter(plugin -> !disabled.contains(Names.key(plugin.name())))
                .filter(plugin -> plugin.definition().appliesTo(maintask)
                        || plugin.definition().scope() == PluginDefinition.Scope.SUBTASK
                        && chosen.contains(Names.key(plugin.name())))
                .sorted(Comparator.comparing((Plugin plugin) -> plugin.definition().scope())
                        .thenComparing(plugin -> Names.key(plugin.name())))
                .toList();
    }

    private static Set<String> keys(List<String> names) {
        Set<String> keys = new HashSet<>();
        names.forEach(name -> keys.add(Names.key(name)));
        return keys;
    }

    /** The changed request and the plugins that had request operations. */
    public record RequestResult(BackendRequest request, List<Plugin.Applied> applied) {
    }

    public static RequestResult applyRequest(BackendRequest request, List<Plugin> plugins, RequestSpec spec,
                                             Resolver resolver) {
        List<Plugin.Applied> applied = new ArrayList<>();
        if (plugins.stream().allMatch(plugin -> plugin.definition().request().isEmpty())) {
            return new RequestResult(request, applied);
        }
        Set<String> ownHeaders = new HashSet<>(BROKER_REQUEST_HEADERS);
        spec.headers().keySet().forEach(name -> ownHeaders.add(Names.key(name)));
        Set<String> ownQuery = spec.queryParams().keySet();
        List<List<String>> ownBody = templatePaths(spec);
        boolean jsonBody = request.previewBody() != null && request.previewBody().isObject()
                && request.contentType() != null && request.contentType().toLowerCase(Locale.ROOT).contains("json");

        Map<String, List<String>> headers = new LinkedHashMap<>(request.headers());
        List<String[]> query = parseQuery(request.uri().getRawQuery());
        ObjectNode body = jsonBody ? (ObjectNode) request.previewBody().deepCopy() : null;
        for (Plugin plugin : plugins) {
            if (plugin.definition().request().isEmpty()) {
                continue;
            }
            applied.add(plugin.applied());
            for (PluginDefinition.Operation operation : plugin.definition().request()) {
                JsonNode value = value(operation, resolver);
                if (value == null) {
                    continue;
                }
                switch (operation.target()) {
                    case HEADER -> {
                        if (!ownHeaders.contains(Names.key(operation.name()))) {
                            requestHeader(headers, operation, value);
                        }
                    }
                    case QUERY -> {
                        if (!ownQuery.contains(operation.name())) {
                            queryParameter(query, operation, value);
                        }
                    }
                    case BODY -> {
                        if (body != null && ownBody != null && ownBody.stream().noneMatch(own -> overlaps(own, operation.path()))) {
                            body(body, operation, value);
                        }
                    }
                }
            }
        }
        URI uri = withQuery(request.uri(), query);
        byte[] bytes = body == null ? request.body() : Json.writeBytes(body);
        JsonNode preview = body == null ? request.previewBody() : body;
        return new RequestResult(new BackendRequest(request.method(), uri, headers, bytes, request.contentType(),
                preview), applied);
    }

    /** What response operations read besides the request: the service's answer and the timings. */
    public record ResponseValues(Integer backendStatus, Map<String, String> backendHeaders, String outcome,
                                 Long backendDurationMs, Instant receivedAt, RequestScope scope) {
    }

    /** The request's placeholders plus the response-only ones. */
    public static Resolver responseResolver(Resolver request, ResponseValues values) {
        return placeholder -> {
            String path = placeholder.path();
            return switch (placeholder.root()) {
                case Placeholder.BACKEND -> {
                    if ("status".equals(path)) {
                        yield values.backendStatus() == null ? Json.missing() : Json.number(values.backendStatus());
                    }
                    if ("durationMs".equals(path)) {
                        yield values.backendDurationMs() == null ? Json.missing() : Json.number(values.backendDurationMs());
                    }
                    if (path.startsWith("header.")) {
                        String header = values.backendHeaders().get(Names.key(path.substring("header.".length())));
                        yield header == null ? Json.missing() : Json.string(header);
                    }
                    yield Json.missing();
                }
                case Placeholder.OUTCOME -> values.outcome() == null ? Json.missing() : Json.string(values.outcome());
                case PluginDefinition.DURATION_MS -> {
                    Instant now = values.scope() == null ? Instant.now() : values.scope().clock().instant();
                    yield values.receivedAt() == null ? Json.missing()
                            : Json.number(Duration.between(values.receivedAt(), now).toMillis());
                }
                case PluginDefinition.REQUEST_RECEIVED_AT -> ContextPlaceholders.localTime(values.receivedAt(),
                        values.scope());
                default -> request.resolve(placeholder);
            };
        };
    }

    /**
     * The frontend's response after the plugins: {@code headers} to write besides the broker's own, the body (a
     * JSON object changed in place of a copy, anything else as it was).
     */
    public record ResponseResult(Map<String, String> headers, JsonNode body, List<Plugin.Applied> applied) {
    }

    /**
     * @param headers headers the response already carries (the broker's, or the file's), by their spelling
     * @param body    the JSON the frontend gets; null for a file or a body that is not JSON
     */
    public static ResponseResult applyResponse(Map<String, String> headers, JsonNode body, List<Plugin> plugins,
                                               Resolver resolver) {
        Map<String, String> result = new LinkedHashMap<>(headers);
        List<Plugin.Applied> applied = new ArrayList<>();
        ObjectNode object = body != null && body.isObject() ? (ObjectNode) body.deepCopy() : null;
        for (Plugin plugin : plugins) {
            if (plugin.definition().response().isEmpty()) {
                continue;
            }
            applied.add(plugin.applied());
            for (PluginDefinition.Operation operation : plugin.definition().response()) {
                JsonNode value = value(operation, resolver);
                if (value == null) {
                    continue;
                }
                if (operation.target() == PluginDefinition.Target.HEADER) {
                    responseHeader(result, operation, value);
                } else if (object != null) {
                    body(object, operation, value);
                }
            }
        }
        return new ResponseResult(result, object != null ? object : body, applied);
    }

    /** The rendered value; {@link Json#nullNode()} for remove; null when the operation is to be skipped. */
    private static JsonNode value(PluginDefinition.Operation operation, Resolver resolver) {
        if (operation.op() == PluginDefinition.Op.REMOVE) {
            return Json.nullNode();
        }
        JsonNode value = operation.value().render(resolver, true);
        return Json.isMissing(value) ? null : value;
    }

    private static void requestHeader(Map<String, List<String>> headers, PluginDefinition.Operation operation,
                                      JsonNode value) {
        String existing = existingKey(headers.keySet(), operation.name());
        switch (operation.op()) {
            case REMOVE -> {
                if (existing != null) {
                    headers.remove(existing);
                }
            }
            case SET_IF_ABSENT -> {
                if (existing == null && !value.isNull()) {
                    headers.put(operation.name(), List.of(Json.text(value)));
                }
            }
            case SET -> {
                if (existing != null) {
                    headers.remove(existing);
                }
                if (!value.isNull()) {
                    headers.put(operation.name(), List.of(Json.text(value)));
                }
            }
        }
    }

    private static void responseHeader(Map<String, String> headers, PluginDefinition.Operation operation,
                                       JsonNode value) {
        String existing = existingKey(headers.keySet(), operation.name());
        switch (operation.op()) {
            case REMOVE -> {
                if (existing != null) {
                    headers.remove(existing);
                }
            }
            case SET_IF_ABSENT -> {
                if (existing == null && !value.isNull()) {
                    headers.put(operation.name(), Json.text(value));
                }
            }
            case SET -> {
                if (existing != null) {
                    headers.remove(existing);
                }
                if (!value.isNull()) {
                    headers.put(operation.name(), Json.text(value));
                }
            }
        }
    }

    private static String existingKey(Collection<String> names, String name) {
        return names.stream().filter(candidate -> Names.sameName(candidate, name)).findFirst().orElse(null);
    }

    private static void queryParameter(List<String[]> query, PluginDefinition.Operation operation, JsonNode value) {
        boolean present = query.stream().anyMatch(pair -> pair[0].equals(operation.name()));
        switch (operation.op()) {
            case REMOVE -> query.removeIf(pair -> pair[0].equals(operation.name()));
            case SET_IF_ABSENT -> {
                if (!present && !value.isNull()) {
                    query.add(new String[]{operation.name(), Json.text(value)});
                }
            }
            case SET -> {
                query.removeIf(pair -> pair[0].equals(operation.name()));
                if (!value.isNull()) {
                    query.add(new String[]{operation.name(), Json.text(value)});
                }
            }
        }
    }

    private static void body(ObjectNode body, PluginDefinition.Operation operation, JsonNode value) {
        List<String> path = operation.path();
        ObjectNode parent = body;
        for (String segment : path.subList(0, path.size() - 1)) {
            JsonNode next = parent.get(segment);
            if (next == null || next.isNull()) {
                if (operation.op() == PluginDefinition.Op.REMOVE) {
                    return;
                }
                next = parent.putObject(segment);
            } else if (!next.isObject()) {
                // the path runs into a value: the plugin does not replace it
                return;
            }
            parent = (ObjectNode) next;
        }
        String last = path.getLast();
        switch (operation.op()) {
            case REMOVE -> parent.remove(last);
            case SET_IF_ABSENT -> {
                if (!parent.has(last)) {
                    parent.set(last, value);
                }
            }
            case SET -> parent.set(last, value);
        }
    }

    /**
     * Paths the definition's body template writes (its leaves); null when the body is not a template object the
     * plugins may add to: a single placeholder for the whole body, multipart, no body.
     */
    private static List<List<String>> templatePaths(RequestSpec spec) {
        BodyMode mode = spec.body().mode();
        if (mode == BodyMode.PASS_THROUGH) {
            return List.of();
        }
        if (mode != BodyMode.TEMPLATE || spec.body().rawTemplate() == null || !spec.body().rawTemplate().isObject()) {
            return null;
        }
        List<List<String>> paths = new ArrayList<>();
        collect(spec.body().rawTemplate(), new ArrayList<>(), paths);
        return paths;
    }

    private static void collect(JsonNode node, List<String> prefix, List<List<String>> into) {
        if (node.isObject() && !node.isEmpty()) {
            for (Map.Entry<String, JsonNode> entry : Json.fields(node)) {
                List<String> path = new ArrayList<>(prefix);
                path.add(entry.getKey());
                collect(entry.getValue(), path, into);
            }
        } else if (!prefix.isEmpty()) {
            into.add(List.copyOf(prefix));
        }
    }

    /** One path is the other or lies inside it. */
    private static boolean overlaps(List<String> own, List<String> path) {
        int common = Math.min(own.size(), path.size());
        return own.subList(0, common).equals(path.subList(0, common));
    }

    private static List<String[]> parseQuery(String raw) {
        List<String[]> pairs = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return pairs;
        }
        for (String part : raw.split("&")) {
            int equals = part.indexOf('=');
            String name = URLDecoder.decode(equals < 0 ? part : part.substring(0, equals), StandardCharsets.UTF_8);
            String value = equals < 0 ? "" : URLDecoder.decode(part.substring(equals + 1), StandardCharsets.UTF_8);
            pairs.add(new String[]{name, value});
        }
        return pairs;
    }

    private static URI withQuery(URI uri, List<String[]> query) {
        StringBuilder raw = new StringBuilder();
        for (String[] pair : query) {
            if (!raw.isEmpty()) {
                raw.append('&');
            }
            raw.append(URLEncoder.encode(pair[0], StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(pair[1], StandardCharsets.UTF_8));
        }
        String text = uri.toString();
        int question = text.indexOf('?');
        String base = question < 0 ? text : text.substring(0, question);
        return URI.create(raw.isEmpty() ? base : base + "?" + raw);
    }
}
