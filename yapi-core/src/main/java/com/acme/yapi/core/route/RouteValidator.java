package com.acme.yapi.core.route;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.acme.yapi.core.naming.Names;
import com.acme.yapi.core.template.JsonTemplate;
import com.acme.yapi.core.template.Placeholder;
import com.acme.yapi.core.template.TemplateString;
import com.acme.yapi.core.validation.FieldError;

import tools.jackson.databind.JsonNode;

/**
 * Validates a route (method, path template and definition) the way the central admin does before saving and the
 * way the broker does before loading. Errors carry form paths: {@code httpMethod}, {@code pathTemplate},
 * {@code definition.request.pathVariables.id}.
 */
public final class RouteValidator {

    /**
     * Shown to users; {@code X-User-*} stands for every header starting with {@code X-User-} except those in
     * {@link #FORBIDDEN_HEADER_EXCEPTIONS}.
     */
    public static final List<String> FORBIDDEN_HEADERS =
            List.of("Authorization", "X-User-*", "X-uid", "Host", "Content-Length", "Connection", "Transfer-Encoding");

    private static final Set<String> FORBIDDEN_EXACT =
            Set.of("authorization", "x-uid", "host", "content-length", "connection", "transfer-encoding");
    /**
     * Client headers that only look like identity headers: {@code X-User-Agent} is the frontend's header, passed on
     * as {@code ${header.X-User-Agent}} (the login service binds the session to it).
     */
    public static final List<String> FORBIDDEN_HEADER_EXCEPTIONS = List.of("X-User-Agent");
    private static final Set<String> ALLOWED_USER_HEADERS = Set.of("x-user-agent");
    private static final Pattern HEADER_NAME = Pattern.compile("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$");

    private RouteValidator() {
    }

    /**
     * Headers of the frontend request that {@code ${header.<name>}} may read; {@code readableHeaders} null means any.
     */
    public record Context(Set<String> readableHeaders) {

        public static Context of(Collection<String> readableHeaders) {
            return new Context(readableHeaders.stream().map(Names::key).collect(Collectors.toUnmodifiableSet()));
        }

        /**
         * Accepts every header. For components that do not own the readable-header list, such as the environment
         * Admin API; the broker still refuses to read a header outside its list.
         */
        public static Context anyHeader() {
            return new Context(null);
        }

        public boolean canRead(String header) {
            return header != null && (readableHeaders == null || readableHeaders.contains(Names.key(header)));
        }
    }

    public static boolean isForbiddenHeader(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        return FORBIDDEN_EXACT.contains(key) || key.startsWith("x-user-") && !ALLOWED_USER_HEADERS.contains(key);
    }

    public static List<FieldError> validate(String httpMethod, String pathTemplate, JsonNode definition, Context context) {
        List<FieldError> errors = new ArrayList<>();
        Optional<HttpMethodName> method = HttpMethodName.parse(httpMethod);
        if (method.isEmpty() || !method.get().name().equals(httpMethod)) {
            errors.add(new FieldError("httpMethod", "GET, POST, PUT, PATCH ya da DELETE olmalı"));
        }
        List<String> pathProblems = PathTemplate.problems(pathTemplate);
        pathProblems.forEach(problem -> errors.add(new FieldError("pathTemplate", problem)));

        RouteDefinitionParser.Parsed parsed = RouteDefinitionParser.parse(definition);
        parsed.errors().forEach(error -> errors.add(error.prefixed("definition")));
        if (!parsed.valid()) {
            return errors;
        }
        RouteDefinition route = parsed.definition();
        RequestSpec request = route.request();

        if (pathProblems.isEmpty()) {
            Set<String> variables = PathTemplate.parse(pathTemplate).variables();
            for (String variable : variables) {
                if (!request.pathVariables().containsKey(variable)) {
                    errors.add(new FieldError("definition.request.pathVariables." + variable,
                            "Path şablonundaki {" + variable + "} eşlenmedi"));
                }
            }
            for (String name : request.pathVariables().keySet()) {
                if (!variables.contains(name)) {
                    errors.add(new FieldError("definition.request.pathVariables." + name,
                            "Path şablonunda {" + name + "} yok"));
                }
            }
        }
        for (Map.Entry<String, TemplateString> entry : request.pathVariables().entrySet()) {
            checkTemplate(entry.getValue(), "definition.request.pathVariables." + entry.getKey(), request, context,
                    true, errors);
        }
        for (Map.Entry<String, TemplateString> entry : request.queryParams().entrySet()) {
            checkTemplate(entry.getValue(), "definition.request.queryParams." + entry.getKey(), request, context,
                    false, errors);
        }
        for (Map.Entry<String, TemplateString> entry : request.headers().entrySet()) {
            String path = "definition.request.headers." + entry.getKey();
            if (!HEADER_NAME.matcher(entry.getKey()).matches()) {
                errors.add(new FieldError(path, "Geçersiz header adı"));
            } else if (isForbiddenHeader(entry.getKey())) {
                errors.add(new FieldError(path, "Bu header'ı yalnızca broker doldurur: " + entry.getKey()));
            }
            checkTemplate(entry.getValue(), path, request, context, false, errors);
        }

        BodySpec body = request.body();
        if (method.isPresent() && !method.get().allowsBody() && body.mode() != BodyMode.NONE) {
            errors.add(new FieldError("definition.request.body.mode", method.get() + " isteğinde body modu NONE olmalı"));
        }
        if (body.mode() == BodyMode.TEMPLATE && body.template() != null) {
            for (JsonTemplate.Located located : body.template().placeholders()) {
                String path = located.path().isEmpty()
                        ? "definition.request.body.template"
                        : "definition.request.body.template." + located.path();
                checkPlaceholder(located.placeholder(), path, request, context, false, errors);
            }
            checkUnclosed(body.rawTemplate(), "definition.request.body.template", errors);
        }
        if (body.mode() == BodyMode.MULTIPART) {
            for (int i = 0; i < body.parts().size(); i++) {
                PartSpec part = body.parts().get(i);
                String path = "definition.request.body.parts[" + i + "].value";
                checkTemplate(part.value(), path, request, context, false, errors);
                if (part.file()) {
                    checkFilePart(part, path, request, errors);
                }
            }
        }
        if (route.maxAttempts() > 1 && method.isPresent() && method.get() != HttpMethodName.GET) {
            errors.add(new FieldError("definition.retry.maxAttempts", "Yeniden deneme yalnızca GET için açılabilir"));
        }
        return errors;
    }

    private static void checkTemplate(TemplateString template, String path, RequestSpec request, Context context,
                                      boolean pathVariable, List<FieldError> errors) {
        if (template.hasUnclosedPlaceholder()) {
            errors.add(new FieldError(path, "Kapanmamış yer tutucu: " + template.source()));
        }
        for (Placeholder placeholder : template.placeholders()) {
            checkPlaceholder(placeholder, path, request, context, pathVariable, errors);
        }
    }

    private static void checkPlaceholder(Placeholder placeholder, String path, RequestSpec request, Context context,
                                         boolean pathVariable, List<FieldError> errors) {
        switch (placeholder.root()) {
            case Placeholder.REQUEST -> checkRequestReference(placeholder, path, request, pathVariable, errors);
            case Placeholder.HEADER -> {
                if (placeholder.path().isEmpty()) {
                    errors.add(new FieldError(path, "Header adı eksik: " + placeholder));
                } else if (!context.canRead(placeholder.path())) {
                    errors.add(new FieldError(path, "Bu header okunamaz: " + placeholder.path()));
                }
            }
            case Placeholder.USER -> {
                if (!"id".equals(placeholder.path()) && !"permission".equals(placeholder.path())) {
                    errors.add(new FieldError(path,
                            "Kullanıcı için yalnızca ${user.id} ve ${user.permission} kullanılabilir"));
                }
            }
            case Placeholder.REQUEST_ID -> {
                if (!placeholder.path().isEmpty()) {
                    errors.add(new FieldError(path, "Bilinmeyen yer tutucu: " + placeholder));
                }
            }
            case Placeholder.BACKEND, Placeholder.OUTCOME ->
                    errors.add(new FieldError(path, placeholder + " yalnızca yanıt profillerinde kullanılır"));
            default -> errors.add(new FieldError(path, "Bilinmeyen yer tutucu: " + placeholder));
        }
    }

    private static void checkRequestReference(Placeholder placeholder, String path, RequestSpec request,
                                              boolean pathVariable, List<FieldError> errors) {
        String reference = placeholder.path();
        if (reference.isEmpty()) {
            errors.add(new FieldError(path, "Alan adı eksik: " + placeholder));
            return;
        }
        if (RouteDefinitionParser.ENVELOPE_FIELDS.contains(reference)) {
            // envelope fields are read without a field definition; nothing guarantees they are sent
            if (pathVariable) {
                errors.add(new FieldError(path,
                        "Path değişkeni yalnızca zorunlu ya da varsayılanı olan bir alana bağlanabilir: " + reference));
            }
            return;
        }
        Optional<FieldSpec> exact = request.field(reference);
        if (exact.isPresent()) {
            if (pathVariable && !exact.get().alwaysPresent()) {
                errors.add(new FieldError(path,
                        "Path değişkeni yalnızca zorunlu ya da varsayılanı olan bir alana bağlanabilir: " + reference));
            }
            return;
        }
        boolean insideObjectField = request.fields().stream()
                .anyMatch(field -> field.type() == FieldType.OBJECT && reference.startsWith(field.name() + "."));
        boolean parentOfFields = request.fields().stream()
                .anyMatch(field -> field.name().startsWith(reference + "."));
        if (!insideObjectField && !parentOfFields) {
            errors.add(new FieldError(path, "Tanımsız alan: " + reference));
        } else if (pathVariable) {
            errors.add(new FieldError(path,
                    "Path değişkeni yalnızca zorunlu ya da varsayılanı olan bir alana bağlanabilir: " + reference));
        }
    }

    private static void checkFilePart(PartSpec part, String path, RequestSpec request, List<FieldError> errors) {
        TemplateString value = part.value();
        if (!value.isSinglePlaceholder()) {
            errors.add(new FieldError(path, "Dosya parçası tek bir ${request.<alan>} yer tutucusu olmalı"));
            return;
        }
        Placeholder placeholder = value.placeholders().getFirst();
        Optional<FieldSpec> field = Placeholder.REQUEST.equals(placeholder.root())
                ? request.field(placeholder.path()) : Optional.empty();
        if (field.isEmpty() || field.get().type() != FieldType.OBJECT) {
            errors.add(new FieldError(path,
                    "Dosya parçası {fileName, contentType, base64} taşıyan object tipinde bir alana bağlanmalı"));
        }
    }

    private static void checkUnclosed(JsonNode template, String path, List<FieldError> errors) {
        if (template == null) {
            return;
        }
        if (template.isString()) {
            if (TemplateString.parse(template.stringValue()).hasUnclosedPlaceholder()) {
                errors.add(new FieldError(path, "Kapanmamış yer tutucu: " + template.stringValue()));
            }
            return;
        }
        if (template.isObject()) {
            for (Map.Entry<String, JsonNode> entry : template.properties()) {
                checkUnclosed(entry.getValue(), path + "." + entry.getKey(), errors);
            }
        } else if (template.isArray()) {
            int index = 0;
            for (JsonNode element : template) {
                checkUnclosed(element, path + "[" + index++ + "]", errors);
            }
        }
    }
}
