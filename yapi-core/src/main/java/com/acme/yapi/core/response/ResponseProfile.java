package com.acme.yapi.core.response;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.naming.Names;
import com.acme.yapi.core.request.RequestScope;
import com.acme.yapi.core.template.ContextPlaceholders;
import com.acme.yapi.core.template.JsonTemplate;
import com.acme.yapi.core.template.Placeholder;
import com.acme.yapi.core.template.Resolver;
import com.acme.yapi.core.template.TemplateString;
import com.acme.yapi.core.validation.FieldError;

import tools.jackson.databind.JsonNode;

/**
 * A compiled response profile: the envelope a project's frontend expects around backend responses.
 *
 * <pre>
 * {"success": {"httpStatus": 200, "body": {"BACKEND_STATUS": "${backend.status}", "data": "${backend.body}"}},
 *  "error":   {"httpStatus": 400, "body": {"status": 400, "reason": "LogProject Hata!"}}}
 * </pre>
 *
 * The success template is used for 2xx responses; the error template for 4xx, 5xx, timeouts and connection
 * failures. Each may hold {@code cases}: templates tried in order, the first whose {@code when} conditions all hold
 * is used, and the template itself when none does. A 5xx body is not handed to the templates unless the profile
 * sets {@code exposeServerErrorBody}, so stack traces do not leak to the frontend.
 *
 * <p>In a profile a placeholder without a value is written as {@code null} (the field stays) and as empty text
 * inside text; only a 5xx body that is not exposed leaves its field out, as it always did.
 */
public final class ResponseProfile {

    private static final Set<String> ROOT_KEYS = Set.of("success", "error", "exposeServerErrorBody");
    private static final Set<String> TEMPLATE_KEYS = Set.of("httpStatus", "body", "cases");
    private static final Set<String> CASE_KEYS = Set.of("when", "httpStatus", "body");
    /** The operators a {@code when} condition can use, besides its {@code value}. */
    public static final List<String> OPERATORS = List.of("exists", "equals", "notEquals", "in");
    /** {@code httpStatus} may take the service's status instead of a number. */
    public static final String BACKEND_STATUS = "${backend.status}";
    private static final Pattern PATH_SEGMENT = Pattern.compile("[\\p{L}\\p{N}_-]+");
    private static final Pattern HEADER_NAME = Pattern.compile("[A-Za-z0-9!#$%&'*+.^_`|~-]+");

    private final Section success;
    private final Section error;
    private final boolean exposeServerErrorBody;

    private ResponseProfile(Section success, Section error, boolean exposeServerErrorBody) {
        this.success = success;
        this.error = error;
        this.exposeServerErrorBody = exposeServerErrorBody;
    }

    /** A status of {@code 0} stands for {@code ${backend.status}}. */
    private record Template(int httpStatus, JsonTemplate body) {

        boolean fromBackend() {
            return httpStatus == 0;
        }
    }

    private record Condition(Placeholder value, String operator, JsonNode operand) {
    }

    private record Case(List<Condition> when, Template template) {
    }

    private record Section(List<Case> cases, Template fallback) {
    }

    public record Parsed(ResponseProfile profile, List<FieldError> errors) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    /**
     * What the profile renders from: the backend's status, body and headers, or a null status after a timeout or
     * a connection failure.
     *
     * @param headers the service's response headers by {@link Names#key}; first value of each
     * @param scope   what the request-wide placeholders read; null leaves them without a value
     */
    public record BackendResult(Integer status, JsonNode body, Outcome outcome, String requestId,
                                Map<String, String> headers, RequestScope scope) {

        public BackendResult(Integer status, JsonNode body, Outcome outcome, String requestId) {
            this(status, body, outcome, requestId, Map.of(), null);
        }

        public BackendResult(Integer status, JsonNode body, Outcome outcome, String requestId,
                             Map<String, String> headers) {
            this(status, body, outcome, requestId, headers, null);
        }
    }

    /**
     * A rendered response and how it was chosen.
     *
     * @param template    {@code success} or {@code error}
     * @param matchedCase index of the case whose conditions held; null when the template itself was used
     */
    public record Rendered(ClientResponse response, String template, Integer matchedCase) {
    }

    public static Parsed parse(JsonNode definition) {
        List<FieldError> errors = new ArrayList<>();
        if (definition == null || !definition.isObject()) {
            errors.add(new FieldError("", "Profil bir JSON nesnesi olmalı"));
            return new Parsed(null, errors);
        }
        for (String key : Json.fieldNames(definition)) {
            if (!ROOT_KEYS.contains(key)) {
                errors.add(new FieldError(key, "Bilinmeyen alan"));
            }
        }
        JsonNode expose = definition.get("exposeServerErrorBody");
        if (expose != null && !expose.isBoolean()) {
            errors.add(new FieldError("exposeServerErrorBody", "true ya da false olmalı"));
        }
        Section success = parseSection(definition.get("success"), "success", errors);
        Section error = parseSection(definition.get("error"), "error", errors);
        return new Parsed(errors.isEmpty() ? new ResponseProfile(success, error, Json.isTrue(expose)) : null, errors);
    }

    private static Section parseSection(JsonNode node, String path, List<FieldError> errors) {
        if (node == null || !node.isObject()) {
            errors.add(new FieldError(path, "Zorunlu; httpStatus ve body içeren bir nesne olmalı"));
            return null;
        }
        for (String key : Json.fieldNames(node)) {
            if (!TEMPLATE_KEYS.contains(key)) {
                errors.add(new FieldError(path + "." + key, "Bilinmeyen alan"));
            }
        }
        List<Case> cases = new ArrayList<>();
        JsonNode casesNode = node.get("cases");
        if (casesNode != null && !casesNode.isNull()) {
            if (!casesNode.isArray()) {
                errors.add(new FieldError(path + ".cases", "Dizi olmalı"));
            } else {
                int index = 0;
                for (JsonNode element : casesNode) {
                    Case parsed = parseCase(element, path + ".cases[" + index++ + "]", errors);
                    if (parsed != null) {
                        cases.add(parsed);
                    }
                }
            }
        }
        return new Section(List.copyOf(cases), parseTemplate(node, path, errors));
    }

    private static Case parseCase(JsonNode node, String path, List<FieldError> errors) {
        if (node == null || !node.isObject()) {
            errors.add(new FieldError(path, "when, httpStatus ve body içeren bir nesne olmalı"));
            return null;
        }
        for (String key : Json.fieldNames(node)) {
            if (!CASE_KEYS.contains(key)) {
                errors.add(new FieldError(path + "." + key, "Bilinmeyen alan"));
            }
        }
        JsonNode when = node.get("when");
        List<Condition> conditions = new ArrayList<>();
        if (when == null || !when.isArray() || when.isEmpty()) {
            errors.add(new FieldError(path + ".when", "En az bir koşul içeren bir dizi olmalı"));
        } else {
            int index = 0;
            for (JsonNode element : when) {
                Condition condition = parseCondition(element, path + ".when[" + index++ + "]", errors);
                if (condition != null) {
                    conditions.add(condition);
                }
            }
        }
        Template template = parseTemplate(node, path, errors);
        return template == null ? null : new Case(List.copyOf(conditions), template);
    }

    private static Condition parseCondition(JsonNode node, String path, List<FieldError> errors) {
        if (node == null || !node.isObject()) {
            errors.add(new FieldError(path, "value ve bir operatör içeren bir nesne olmalı"));
            return null;
        }
        List<String> operators = new ArrayList<>();
        for (String key : Json.fieldNames(node)) {
            if (OPERATORS.contains(key)) {
                operators.add(key);
            } else if (!"value".equals(key)) {
                errors.add(new FieldError(path + "." + key, "Bilinmeyen operatör; " + String.join(", ", OPERATORS)
                        + " kullanılabilir"));
            }
        }
        Placeholder value = null;
        JsonNode valueNode = node.get("value");
        TemplateString template = Json.isString(valueNode) ? TemplateString.parse(valueNode.stringValue()) : null;
        if (template == null || !template.isSinglePlaceholder()) {
            errors.add(new FieldError(path + ".value", "Tek bir yer tutucu olmalı, ör. \"${backend.body.status}\""));
        } else {
            value = template.placeholders().getFirst();
            String problem = placeholderProblem(value);
            if (problem != null) {
                errors.add(new FieldError(path + ".value", problem));
            }
        }
        if (operators.size() != 1) {
            errors.add(new FieldError(path, "Tam bir operatör olmalı: " + String.join(", ", OPERATORS)));
            return null;
        }
        String operator = operators.getFirst();
        JsonNode operand = node.get(operator);
        if ("exists".equals(operator) && !operand.isBoolean()) {
            errors.add(new FieldError(path + ".exists", "true ya da false olmalı"));
        }
        if ("in".equals(operator) && !operand.isArray()) {
            errors.add(new FieldError(path + ".in", "Değerlerin dizisi olmalı"));
        }
        return value == null ? null : new Condition(value, operator, operand);
    }

    private static Template parseTemplate(JsonNode node, String path, List<FieldError> errors) {
        JsonNode status = node.get("httpStatus");
        int httpStatus = -1;
        if (status != null && status.isString() && BACKEND_STATUS.equals(status.stringValue())) {
            httpStatus = 0;
        } else if (status == null || !status.isIntegralNumber() || status.intValue() < 100 || status.intValue() > 599) {
            errors.add(new FieldError(path + ".httpStatus",
                    "100 ile 599 arasında bir HTTP status ya da \"" + BACKEND_STATUS + "\" olmalı"));
        } else {
            httpStatus = status.intValue();
        }
        JsonNode body = node.get("body");
        if (body == null) {
            errors.add(new FieldError(path + ".body", "Zorunlu"));
            return null;
        }
        JsonTemplate template = JsonTemplate.compile(body);
        for (JsonTemplate.Located located : template.placeholders()) {
            String where = located.path().isEmpty() ? path + ".body" : path + ".body." + located.path();
            String problem = placeholderProblem(located.placeholder());
            if (problem != null) {
                errors.add(new FieldError(where, problem));
            }
        }
        checkUnclosed(body, path + ".body", errors);
        return new Template(httpStatus, template);
    }

    /** Why a placeholder cannot be used in a profile, or null when it can. */
    private static String placeholderProblem(Placeholder placeholder) {
        String path = placeholder.path();
        return switch (placeholder.root()) {
            case Placeholder.BACKEND -> {
                if ("status".equals(path) || "body".equals(path)) {
                    yield null;
                }
                if (path.startsWith("body.")) {
                    yield bodyPath(path.substring("body.".length())) == null
                            ? "Geçersiz yol: " + placeholder + "; noktalı alan adları ya da dizi indeksleri olmalı"
                            : null;
                }
                if (path.startsWith("header.")) {
                    yield HEADER_NAME.matcher(path.substring("header.".length())).matches()
                            ? null : "Geçersiz header adı: " + placeholder;
                }
                yield "Profilde kullanılamayan yer tutucu: " + placeholder;
            }
            case Placeholder.OUTCOME, Placeholder.REQUEST_ID -> path.isEmpty() ? null
                    : "Profilde kullanılamayan yer tutucu: " + placeholder;
            default -> ContextPlaceholders.handles(placeholder) ? ContextPlaceholders.problem(placeholder)
                    : "Profilde kullanılamayan yer tutucu: " + placeholder;
        };
    }

    /** The segments of {@code status-code} or {@code items.0.id}; null when the path is malformed. */
    private static List<String> bodyPath(String path) {
        if (path.isEmpty() || path.startsWith(".") || path.endsWith(".")) {
            return null;
        }
        List<String> segments = Arrays.asList(path.split("\\.", -1));
        for (String segment : segments) {
            if (!PATH_SEGMENT.matcher(segment).matches()) {
                return null;
            }
        }
        return segments;
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

    /** Wraps a backend result; 2xx uses the success template, everything else the error template. */
    public ClientResponse render(BackendResult result) {
        return evaluate(result).response();
    }

    /** As {@link #render}, telling which template and case were used (the profile preview shows it). */
    public Rendered evaluate(BackendResult result) {
        boolean ok = result.outcome() == Outcome.SUCCESS;
        Section section = ok ? success : error;
        boolean bodyAllowed = ok || exposeServerErrorBody
                || (result.status() != null && result.status() >= 400 && result.status() < 500);
        Resolver raw = placeholder -> value(placeholder, result, bodyAllowed);
        Integer matched = null;
        Template template = section.fallback();
        for (int i = 0; i < section.cases().size(); i++) {
            Case candidate = section.cases().get(i);
            if (candidate.when().stream().allMatch(condition -> holds(condition, raw))) {
                matched = i;
                template = candidate.template();
                break;
            }
        }
        // without a value a field is written as null; a 5xx body that is not exposed stays out
        Resolver rendering = placeholder -> {
            JsonNode value = raw.resolve(placeholder);
            if (Json.isMissing(value) && !(isBody(placeholder) && !bodyAllowed)) {
                return Json.nullNode();
            }
            return value;
        };
        JsonNode body = template.body().render(rendering, true, true);
        ClientResponse response = new ClientResponse(status(template, result), Json.isMissing(body) ? Json.nullNode() : body);
        return new Rendered(response, ok ? "success" : "error", matched);
    }

    private static int status(Template template, BackendResult result) {
        if (!template.fromBackend()) {
            return template.httpStatus();
        }
        if (result.status() != null) {
            return result.status();
        }
        // no answer from the service: 504 after a timeout, 502 when it could not be reached
        return result.outcome() == Outcome.TIMEOUT ? 504 : 502;
    }

    private static boolean isBody(Placeholder placeholder) {
        return Placeholder.BACKEND.equals(placeholder.root())
                && ("body".equals(placeholder.path()) || placeholder.path().startsWith("body."));
    }

    /** A placeholder's value as conditions see it: missing when there is none. */
    private static JsonNode value(Placeholder placeholder, BackendResult result, boolean bodyAllowed) {
        String path = placeholder.path();
        return switch (placeholder.root()) {
            case Placeholder.BACKEND -> {
                if ("status".equals(path)) {
                    yield result.status() == null ? Json.missing() : Json.number(result.status());
                }
                if (path.startsWith("header.")) {
                    String header = result.headers().get(Names.key(path.substring("header.".length())));
                    yield header == null ? Json.missing() : Json.string(header);
                }
                if (!bodyAllowed || Json.isMissing(result.body())) {
                    yield Json.missing();
                }
                yield "body".equals(path) ? result.body() : Json.at(result.body(), bodyPath(path.substring(5)));
            }
            case Placeholder.OUTCOME -> Json.string(result.outcome().name());
            case Placeholder.REQUEST_ID -> Json.string(result.requestId());
            default -> ContextPlaceholders.handles(placeholder)
                    ? ContextPlaceholders.resolve(placeholder, result.scope()) : Json.missing();
        };
    }

    /**
     * {@code exists} asks whether there is a value ({@code null} in the body counts); {@code equals} and {@code in}
     * compare by JSON type and value; {@code notEquals} also holds when there is no value.
     */
    private static boolean holds(Condition condition, Resolver resolver) {
        JsonNode value = resolver.resolve(condition.value());
        boolean present = !Json.isMissing(value);
        return switch (condition.operator()) {
            case "exists" -> present == condition.operand().booleanValue();
            case "equals" -> present && Json.sameValue(value, condition.operand());
            case "notEquals" -> !present || !Json.sameValue(value, condition.operand());
            case "in" -> {
                if (!present) {
                    yield false;
                }
                for (JsonNode candidate : condition.operand()) {
                    if (Json.sameValue(value, candidate)) {
                        yield true;
                    }
                }
                yield false;
            }
            default -> false;
        };
    }
}
