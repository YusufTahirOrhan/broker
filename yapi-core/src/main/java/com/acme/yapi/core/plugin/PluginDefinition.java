package com.acme.yapi.core.plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.naming.Names;
import com.acme.yapi.core.route.RouteValidator;
import com.acme.yapi.core.template.ContextPlaceholders;
import com.acme.yapi.core.template.JsonTemplate;
import com.acme.yapi.core.template.Placeholder;
import com.acme.yapi.core.template.TemplateString;
import com.acme.yapi.core.validation.FieldError;

import tools.jackson.databind.JsonNode;

/**
 * A plugin's {@code definition}: where it applies and what it adds to requests and responses.
 *
 * <pre>
 * {"scope": "PROJECT", "projects": ["order-service"],
 *  "request":  [{"op": "set", "target": "header", "name": "X-Request-Time", "value": "${now}"}],
 *  "response": [{"op": "setIfAbsent", "target": "body", "name": "meta.durationMs", "value": "${durationMs}"}]}
 * </pre>
 *
 * @param projects registered spelling of the projects of a {@code PROJECT} plugin; empty otherwise
 */
public record PluginDefinition(Scope scope, List<String> projects, List<Operation> request,
                               List<Operation> response) {

    public enum Scope {
        GLOBAL, PROJECT, SUBTASK
    }

    public enum Op {
        SET("set"), SET_IF_ABSENT("setIfAbsent"), REMOVE("remove");

        private final String json;

        Op(String json) {
            this.json = json;
        }

        public String json() {
            return json;
        }

        static Op parse(String text) {
            return Arrays.stream(values()).filter(op -> op.json.equals(text)).findFirst().orElse(null);
        }
    }

    public enum Target {
        HEADER, QUERY, BODY;

        public String json() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Target parse(String text) {
            return Arrays.stream(values()).filter(target -> target.json().equals(text)).findFirst().orElse(null);
        }
    }

    /**
     * One change: {@code name} is a header name, a query parameter or a dotted body path ({@code path} holds its
     * segments); {@code value} is null for {@code remove}.
     */
    public record Operation(Op op, Target target, String name, List<String> path, JsonTemplate value) {
    }

    public record Parsed(PluginDefinition definition, List<FieldError> errors) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    public static final List<String> OPS = Arrays.stream(Op.values()).map(Op::json).toList();
    public static final List<String> REQUEST_TARGETS = List.of("header", "query", "body");
    public static final List<String> RESPONSE_TARGETS = List.of("header", "body");
    /** Response headers a plugin cannot write: the container and the broker set them. */
    public static final List<String> FORBIDDEN_RESPONSE_HEADERS = List.of("Content-Length", "Transfer-Encoding",
            "X-Request-Id");
    /** As shown in {@code definition-rules}: placeholders only response operations may use. */
    public static final List<String> RESPONSE_ONLY = List.of("${backend.status}", "${backend.header.<ad>}",
            "${outcome}", "${backend.durationMs}", "${durationMs}", "${requestReceivedAt}");
    public static final String DURATION_MS = "durationMs";
    public static final String REQUEST_RECEIVED_AT = "requestReceivedAt";

    private static final Set<String> KEYS = Set.of("scope", "projects", "request", "response");
    private static final Set<String> OPERATION_KEYS = Set.of("op", "target", "name", "value");
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Pattern PATH_SEGMENT = Pattern.compile("[\\p{L}\\p{N}_-]+");
    private static final Pattern QUERY_NAME = Pattern.compile("[^\\s&=#]+");

    public boolean appliesTo(String maintask) {
        return switch (scope) {
            case GLOBAL -> true;
            case PROJECT -> projects.stream().anyMatch(project -> Names.sameName(project, maintask));
            case SUBTASK -> false;
        };
    }

    /**
     * @param readable the frontend headers {@code ${header.*}} may read, as for definitions
     */
    public static Parsed parse(JsonNode node, RouteValidator.Context readable) {
        List<FieldError> errors = new ArrayList<>();
        if (node == null || !node.isObject()) {
            errors.add(new FieldError("", "Eklenti tanımı bir JSON nesnesi olmalı"));
            return new Parsed(null, errors);
        }
        for (String key : Json.fieldNames(node)) {
            if (!KEYS.contains(key)) {
                errors.add(new FieldError(key, "Bilinmeyen alan"));
            }
        }
        Scope scope = null;
        String scopeText = Json.stringField(node, "scope");
        if (scopeText != null) {
            scope = Arrays.stream(Scope.values()).filter(value -> value.name().equals(scopeText)).findFirst()
                    .orElse(null);
        }
        if (scope == null) {
            errors.add(new FieldError("scope", "GLOBAL, PROJECT ya da SUBTASK olmalı"));
        }
        List<String> projects = parseProjects(node.get("projects"), scope, errors);
        List<Operation> request = parseOperations(node.get("request"), "request", false, readable, errors);
        List<Operation> response = parseOperations(node.get("response"), "response", true, readable, errors);
        if (size(node.get("request")) + size(node.get("response")) == 0) {
            errors.add(new FieldError("request", "En az bir istek ya da yanıt işlemi gerekli"));
        }
        PluginDefinition definition = errors.isEmpty()
                ? new PluginDefinition(scope, projects, List.copyOf(request), List.copyOf(response)) : null;
        return new Parsed(definition, errors);
    }

    private static int size(JsonNode node) {
        return node != null && node.isArray() ? node.size() : 0;
    }

    private static List<String> parseProjects(JsonNode node, Scope scope, List<FieldError> errors) {
        boolean given = node != null && !node.isNull();
        if (scope != Scope.PROJECT) {
            if (given) {
                errors.add(new FieldError("projects", "Yalnızca PROJECT kapsamında verilir"));
            }
            return List.of();
        }
        if (!given || !node.isArray() || node.isEmpty()) {
            errors.add(new FieldError("projects", "PROJECT kapsamında en az bir maintask içeren bir dizi olmalı"));
            return List.of();
        }
        Set<String> projects = new LinkedHashSet<>();
        int index = 0;
        for (JsonNode element : node) {
            String maintask = Json.isString(element) ? element.stringValue() : null;
            if (!Names.isValidMaintask(maintask)) {
                errors.add(new FieldError("projects[" + index + "]", "Geçerli bir maintask olmalı"));
            } else {
                projects.add(maintask);
            }
            index++;
        }
        return List.copyOf(projects);
    }

    private static List<Operation> parseOperations(JsonNode node, String phase, boolean response,
                                                   RouteValidator.Context readable, List<FieldError> errors) {
        List<Operation> operations = new ArrayList<>();
        if (node == null || node.isNull()) {
            return operations;
        }
        if (!node.isArray()) {
            errors.add(new FieldError(phase, "İşlemlerin dizisi olmalı"));
            return operations;
        }
        int index = 0;
        for (JsonNode element : node) {
            Operation operation = parseOperation(element, phase + "[" + index++ + "]", response, readable, errors);
            if (operation != null) {
                operations.add(operation);
            }
        }
        return operations;
    }

    private static Operation parseOperation(JsonNode node, String path, boolean response,
                                            RouteValidator.Context readable, List<FieldError> errors) {
        if (node == null || !node.isObject()) {
            errors.add(new FieldError(path, "op, target, name ve value içeren bir nesne olmalı"));
            return null;
        }
        int before = errors.size();
        for (String key : Json.fieldNames(node)) {
            if (!OPERATION_KEYS.contains(key)) {
                errors.add(new FieldError(path + "." + key, "Bilinmeyen alan"));
            }
        }
        Op op = Op.parse(Json.stringField(node, "op"));
        if (op == null) {
            errors.add(new FieldError(path + ".op", String.join(", ", OPS) + " olmalı"));
        }
        Target target = Target.parse(Json.stringField(node, "target"));
        List<String> allowedTargets = response ? RESPONSE_TARGETS : REQUEST_TARGETS;
        if (target == null || !allowedTargets.contains(target.json())) {
            errors.add(new FieldError(path + ".target", (response ? "Yanıtta " : "İstekte ")
                    + String.join(", ", allowedTargets) + " olmalı"));
            target = null;
        }
        String name = Json.stringField(node, "name");
        List<String> segments = List.of();
        if (name == null || name.isBlank()) {
            errors.add(new FieldError(path + ".name", "Zorunlu"));
        } else if (target != null) {
            switch (target) {
                case HEADER -> {
                    if (!HEADER_NAME.matcher(name).matches()) {
                        errors.add(new FieldError(path + ".name", "Geçerli bir header adı olmalı"));
                    } else if (!response && RouteValidator.isForbiddenHeader(name)) {
                        errors.add(new FieldError(path + ".name", "Bu header eklentiyle yazılamaz: " + name));
                    } else if (response && FORBIDDEN_RESPONSE_HEADERS.stream().anyMatch(h -> Names.sameName(h, name))) {
                        errors.add(new FieldError(path + ".name", "Yanıtta bu header yazılamaz: " + name));
                    }
                }
                case QUERY -> {
                    if (!QUERY_NAME.matcher(name).matches()) {
                        errors.add(new FieldError(path + ".name", "Geçerli bir query parametresi adı olmalı"));
                    }
                }
                case BODY -> {
                    segments = bodyPath(name);
                    if (segments == null) {
                        errors.add(new FieldError(path + ".name", "Noktalı bir gövde yolu olmalı, ör. meta.requestTime"));
                        segments = List.of();
                    }
                }
            }
        }
        JsonNode value = node.get("value");
        JsonTemplate template = null;
        if (op == Op.REMOVE) {
            if (value != null) {
                errors.add(new FieldError(path + ".value", "remove işleminde value verilmez"));
            }
        } else if (value == null) {
            errors.add(new FieldError(path + ".value", "Zorunlu"));
        } else {
            template = JsonTemplate.compile(value);
            for (JsonTemplate.Located located : template.placeholders()) {
                String where = located.path().isEmpty() ? path + ".value" : path + ".value." + located.path();
                String problem = placeholderProblem(located.placeholder(), response, readable);
                if (problem != null) {
                    errors.add(new FieldError(where, problem));
                }
            }
            checkUnclosed(value, path + ".value", errors);
        }
        return errors.size() == before ? new Operation(op, target, name, segments, template) : null;
    }

    private static List<String> bodyPath(String name) {
        if (name.startsWith(".") || name.endsWith(".")) {
            return null;
        }
        List<String> segments = Arrays.asList(name.split("\\.", -1));
        return segments.stream().allMatch(segment -> PATH_SEGMENT.matcher(segment).matches()) ? segments : null;
    }

    /** Why a placeholder cannot be used in an operation of this phase, or null when it can. */
    static String placeholderProblem(Placeholder placeholder, boolean response, RouteValidator.Context readable) {
        String path = placeholder.path();
        String responseOnly = placeholder + " yalnızca yanıt işlemlerinde kullanılır";
        return switch (placeholder.root()) {
            case Placeholder.REQUEST -> path.isEmpty() ? "Alan adı eksik: " + placeholder : null;
            case Placeholder.HEADER -> path.isEmpty() ? "Header adı eksik: " + placeholder
                    : readable.canRead(path) ? null : "Bu header okunamaz: " + path;
            case Placeholder.USER -> RouteValidator.USER_FIELDS.contains(path) ? null
                    : "Kullanıcı için yalnızca ${user.id}, ${user.permission} ve ${user.permissions} kullanılabilir";
            case Placeholder.REQUEST_ID -> path.isEmpty() ? null : "Bilinmeyen yer tutucu: " + placeholder;
            case Placeholder.BACKEND -> {
                boolean known = "status".equals(path) || "durationMs".equals(path)
                        || path.startsWith("header.") && HEADER_NAME.matcher(path.substring(7)).matches();
                yield !known ? "Eklentide kullanılamayan yer tutucu: " + placeholder : response ? null : responseOnly;
            }
            case Placeholder.OUTCOME, DURATION_MS, REQUEST_RECEIVED_AT -> !path.isEmpty()
                    ? "Bilinmeyen yer tutucu: " + placeholder : response ? null : responseOnly;
            default -> ContextPlaceholders.handles(placeholder) ? ContextPlaceholders.problem(placeholder)
                    : "Eklentide kullanılamayan yer tutucu: " + placeholder;
        };
    }

    private static void checkUnclosed(JsonNode node, String path, List<FieldError> errors) {
        if (node.isString()) {
            if (TemplateString.parse(node.stringValue()).hasUnclosedPlaceholder()) {
                errors.add(new FieldError(path, "Kapanmamış yer tutucu: " + node.stringValue()));
            }
        } else if (node.isObject()) {
            for (Map.Entry<String, JsonNode> entry : Json.fields(node)) {
                checkUnclosed(entry.getValue(), path + "." + entry.getKey(), errors);
            }
        } else if (node.isArray()) {
            int index = 0;
            for (JsonNode element : node) {
                checkUnclosed(element, path + "[" + index++ + "]", errors);
            }
        }
    }
}
