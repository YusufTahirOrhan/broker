package com.acme.yapi.core.spec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.acme.yapi.core.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads the operations of an OpenAPI 3 document (springdoc's {@code /v3/api-docs}). Operations are matched to
 * routes by method + path, never by operationId: springdoc numbers same-named methods ({@code _1}, {@code _2}) and
 * the numbers shift as controllers change.
 */
public final class OpenApiSpec {

    private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete");
    private static final int MAX_REF_DEPTH = 32;
    private static final String UNRESOLVED = "x-unresolved";

    private OpenApiSpec() {
    }

    /**
     * An operation with every local {@code $ref} resolved.
     *
     * @param routing     the part that matters for routing: parameters, request body and response content types,
     *                    with keys sorted; its SHA-256 is the fingerprint
     */
    public record Operation(String httpMethod, String path, String operationId, String summary,
                            JsonNode parameters, JsonNode requestBody, JsonNode responses,
                            JsonNode routing, String fingerprint) {

        public String key() {
            return key(httpMethod, path);
        }

        public static String key(String httpMethod, String path) {
            return httpMethod.toUpperCase(Locale.ROOT) + " " + normalizePath(path);
        }
    }

    public static List<Operation> operations(JsonNode spec) {
        List<Operation> operations = new ArrayList<>();
        JsonNode paths = spec == null ? null : spec.get("paths");
        if (paths == null || !paths.isObject()) {
            return operations;
        }
        Resolver resolver = new Resolver(spec);
        for (Map.Entry<String, JsonNode> pathEntry : Json.fields(paths)) {
            JsonNode pathItem = resolver.resolve(pathEntry.getValue());
            JsonNode sharedParameters = pathItem.get("parameters");
            for (Map.Entry<String, JsonNode> operationEntry : Json.fields(pathItem)) {
                String method = operationEntry.getKey().toLowerCase(Locale.ROOT);
                if (!METHODS.contains(method)) {
                    continue;
                }
                JsonNode operation = operationEntry.getValue();
                ArrayNode parameters = mergeParameters(resolver, sharedParameters, operation.get("parameters"));
                JsonNode requestBody = operation.has("requestBody")
                        ? resolver.resolve(operation.get("requestBody")) : Json.nullNode();
                JsonNode responses = operation.has("responses")
                        ? resolver.resolve(operation.get("responses")) : Json.object();
                JsonNode routing = routing(parameters, requestBody, responses);
                operations.add(new Operation(method.toUpperCase(Locale.ROOT), normalizePath(pathEntry.getKey()),
                        Json.stringField(operation, "operationId"), Json.stringField(operation, "summary"),
                        parameters, requestBody, responses, routing, fingerprint(routing)));
            }
        }
        operations.sort(Comparator.comparing(Operation::path).thenComparing(Operation::httpMethod));
        return operations;
    }

    public static String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        String normalized = path.startsWith("/") ? path : "/" + path;
        return normalized.length() > 1 && normalized.endsWith("/")
                ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    public static String fingerprint(JsonNode routing) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Json.canonical(routing).toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ArrayNode mergeParameters(Resolver resolver, JsonNode shared, JsonNode own) {
        Map<String, JsonNode> byKey = new LinkedHashMap<>();
        for (JsonNode list : new JsonNode[]{shared, own}) {
            if (list != null && list.isArray()) {
                for (JsonNode parameter : list) {
                    JsonNode resolved = resolver.resolve(parameter);
                    String key = Json.stringField(resolved, "in") + ":" + Json.stringField(resolved, "name");
                    byKey.put(key, resolved);
                }
            }
        }
        ArrayNode result = Json.array();
        byKey.values().forEach(result::add);
        return result;
    }

    private static JsonNode routing(ArrayNode parameters, JsonNode requestBody, JsonNode responses) {
        ObjectNode routing = Json.object();
        List<JsonNode> sorted = new ArrayList<>();
        parameters.forEach(sorted::add);
        sorted.sort(Comparator.comparing((JsonNode p) -> String.valueOf(Json.stringField(p, "in")))
                .thenComparing(p -> String.valueOf(Json.stringField(p, "name"))));
        ArrayNode params = routing.putArray("parameters");
        for (JsonNode parameter : sorted) {
            ObjectNode reduced = params.addObject();
            copy(parameter, reduced, "name", "in", "required", "schema");
        }
        if (requestBody != null && requestBody.isObject()) {
            ObjectNode body = routing.putObject("requestBody");
            copy(requestBody, body, "required", "content");
        }
        Set<String> responseTypes = new TreeSet<>();
        for (Map.Entry<String, JsonNode> response : Json.fields(responses)) {
            JsonNode content = response.getValue().get("content");
            responseTypes.addAll(Json.fieldNames(content));
        }
        ArrayNode types = routing.putArray("responseContentTypes");
        responseTypes.forEach(types::add);
        return Json.canonical(routing);
    }

    private static void copy(JsonNode from, ObjectNode to, String... fields) {
        for (String field : fields) {
            JsonNode value = from.get(field);
            if (value != null) {
                to.set(field, value);
            }
        }
    }

    /** Resolves local {@code $ref}s ({@code #/components/...}); a reference cycle is cut and marked. */
    static final class Resolver {
        private final JsonNode root;
        private final Deque<String> stack = new ArrayDeque<>();

        Resolver(JsonNode root) {
            this.root = root;
        }

        JsonNode resolve(JsonNode node) {
            if (node == null) {
                return Json.nullNode();
            }
            if (node.isObject()) {
                String ref = Json.stringField(node, "$ref");
                if (ref != null && node.has(UNRESOLVED)) {
                    return node; // a cut cycle stays cut when an already resolved tree is resolved again
                }
                if (ref != null) {
                    if (!ref.startsWith("#/") || stack.contains(ref) || stack.size() >= MAX_REF_DEPTH) {
                        ObjectNode marker = Json.object();
                        marker.put("$ref", ref);
                        marker.put(UNRESOLVED, true);
                        return marker;
                    }
                    JsonNode target = root.at(ref.substring(1));
                    stack.push(ref);
                    try {
                        return resolve(target.isMissingNode() ? Json.object() : target);
                    } finally {
                        stack.pop();
                    }
                }
                ObjectNode result = Json.object();
                for (Map.Entry<String, JsonNode> entry : Json.fields(node)) {
                    result.set(entry.getKey(), resolve(entry.getValue()));
                }
                return result;
            }
            if (node.isArray()) {
                ArrayNode result = Json.array();
                for (JsonNode element : node) {
                    result.add(resolve(element));
                }
                return result;
            }
            return node;
        }
    }
}
