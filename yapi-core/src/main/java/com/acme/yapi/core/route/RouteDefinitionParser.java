package com.acme.yapi.core.route;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.naming.Names;
import com.acme.yapi.core.template.JsonTemplate;
import com.acme.yapi.core.template.TemplateString;
import com.acme.yapi.core.validation.FieldError;

import tools.jackson.databind.JsonNode;

/**
 * Reads the {@code definition} JSON of a route into a {@link RouteDefinition}. Structural problems (unknown keys,
 * wrong types, out-of-range numbers) are reported with paths relative to the definition, for example
 * {@code request.fields[0].type}. Cross-field rules live in {@link RouteValidator}.
 */
public final class RouteDefinitionParser {

    /** Fields every request carries; a definition cannot redefine them. */
    public static final Set<String> ENVELOPE_FIELDS = Set.of("maintask", "subtask", "application", "uid");

    private static final Set<String> DEFINITION_KEYS =
            Set.of("request", "response", "timeoutMs", "retry", "logging", "alarm", "alarmService", "plugins",
                    "disabledPlugins");
    private static final Set<String> ALARM_KEYS = Set.of("check", "maintask", "subtask");
    private static final Set<String> ALARM_SERVICE_KEYS = Set.of("resultPath");
    private static final Set<String> REQUEST_KEYS = Set.of("fields", "pathVariables", "queryParams", "headers", "body");
    private static final Set<String> FIELD_KEYS = Set.of("name", "type", "required", "default");
    private static final Set<String> BODY_KEYS = Set.of("mode", "contentType", "template", "parts");
    private static final Set<String> PART_KEYS = Set.of("name", "value", "file");
    private static final Set<String> RESPONSE_KEYS = Set.of("type");
    private static final Set<String> RETRY_KEYS = Set.of("maxAttempts");
    private static final Set<String> LOGGING_KEYS =
            Set.of("logRequestBody", "logResponseBody", "maskFields", "maxBodyBytes", "binaryInfo");
    private static final Pattern FIELD_NAME = Pattern.compile("^[^.\\s]+(\\.[^.\\s]+)*$");

    private RouteDefinitionParser() {
    }

    public record Parsed(RouteDefinition definition, List<FieldError> errors) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    /**
     * Whether a stored definition is an alarm service route: its {@code alarmService} is an object. A JSON
     * {@code null} there is not one, as {@link #parse} reads it; screens, prechecks and the broker decide alike.
     */
    public static boolean isAlarmService(JsonNode definition) {
        return Json.objectField(definition, "alarmService") != null;
    }

    public static Parsed parse(JsonNode node) {
        List<FieldError> errors = new ArrayList<>();
        if (node == null || !node.isObject()) {
            errors.add(new FieldError("", "Tanım bir JSON nesnesi olmalı"));
            return new Parsed(null, errors);
        }
        unknownKeys(node, DEFINITION_KEYS, "", errors);

        RequestSpec request = parseRequest(node.get("request"), errors);
        ResponseType responseType = parseResponse(node.get("response"), errors);
        int timeoutMs = intValue(node.get("timeoutMs"), "timeoutMs", RouteDefinition.DEFAULT_TIMEOUT_MS,
                RouteDefinition.MIN_TIMEOUT_MS, RouteDefinition.MAX_TIMEOUT_MS, errors);
        int maxAttempts = parseRetry(node.get("retry"), errors);
        LoggingSpec logging = parseLogging(node.get("logging"), errors);
        AlarmSpec alarm = parseAlarm(node.get("alarm"), errors);
        AlarmServiceSpec alarmService = parseAlarmService(node.get("alarmService"), errors);

        List<String> plugins = parseNames(node.get("plugins"), "plugins", errors);
        List<String> disabledPlugins = parseNames(node.get("disabledPlugins"), "disabledPlugins", errors);

        RouteDefinition definition = new RouteDefinition(request, responseType, timeoutMs, maxAttempts, logging,
                alarm, alarmService, plugins, disabledPlugins);
        return new Parsed(errors.isEmpty() ? definition : null, errors);
    }

    private static RequestSpec parseRequest(JsonNode node, List<FieldError> errors) {
        if (node == null || node.isNull()) {
            return new RequestSpec(List.of(), Map.of(), Map.of(), Map.of(), BodySpec.none());
        }
        if (!node.isObject()) {
            errors.add(new FieldError("request", "Nesne olmalı"));
            return new RequestSpec(List.of(), Map.of(), Map.of(), Map.of(), BodySpec.none());
        }
        unknownKeys(node, REQUEST_KEYS, "request", errors);
        List<FieldSpec> fields = parseFields(node.get("fields"), errors);
        Map<String, TemplateString> pathVariables = parseStringMap(node.get("pathVariables"), "request.pathVariables", errors);
        Map<String, TemplateString> queryParams = parseStringMap(node.get("queryParams"), "request.queryParams", errors);
        Map<String, TemplateString> headers = parseStringMap(node.get("headers"), "request.headers", errors);
        BodySpec body = parseBody(node.get("body"), errors);
        return new RequestSpec(fields, pathVariables, queryParams, headers, body);
    }

    private static List<FieldSpec> parseFields(JsonNode node, List<FieldError> errors) {
        List<FieldSpec> fields = new ArrayList<>();
        if (node == null || node.isNull()) {
            return fields;
        }
        if (!node.isArray()) {
            errors.add(new FieldError("request.fields", "Liste olmalı"));
            return fields;
        }
        Set<String> seen = new HashSet<>();
        int index = 0;
        for (JsonNode element : node) {
            String path = "request.fields[" + index + "]";
            index++;
            if (!element.isObject()) {
                errors.add(new FieldError(path, "Nesne olmalı"));
                continue;
            }
            unknownKeys(element, FIELD_KEYS, path, errors);
            String name = Json.stringField(element, "name");
            boolean ok = true;
            if (name == null || name.isBlank()) {
                errors.add(new FieldError(path + ".name", "Alan adı zorunlu"));
                ok = false;
            } else if (!FIELD_NAME.matcher(name).matches()) {
                errors.add(new FieldError(path + ".name", "Alan adı noktalarla ayrılmış, boşluksuz bir yol olmalı"));
                ok = false;
            } else if (ENVELOPE_FIELDS.contains(name)) {
                errors.add(new FieldError(path + ".name", "Zarf alanı tanımlanamaz: " + name));
                ok = false;
            } else if (!seen.add(name)) {
                errors.add(new FieldError(path + ".name", "Alan iki kez tanımlanmış: " + name));
                ok = false;
            }
            String typeName = Json.stringField(element, "type");
            FieldType type = typeName == null ? null : FieldType.parse(typeName).orElse(null);
            if (type == null) {
                errors.add(new FieldError(path + ".type",
                        "Tip string, integer, number, boolean, object ya da array olmalı"));
                ok = false;
            }
            boolean required = booleanValue(element.get("required"), path + ".required", false, errors);
            JsonNode defaultValue = element.get("default");
            if (defaultValue != null && defaultValue.isNull()) {
                defaultValue = null;
            }
            if (defaultValue != null && type != null && !type.matches(defaultValue)) {
                errors.add(new FieldError(path + ".default", "Varsayılan değer " + type.jsonName() + " olmalı"));
                ok = false;
            }
            if (defaultValue != null && required) {
                errors.add(new FieldError(path + ".default", "Zorunlu alanın varsayılan değeri olamaz"));
                ok = false;
            }
            if (ok) {
                fields.add(new FieldSpec(name, type, required, defaultValue));
            }
        }
        return fields;
    }

    private static Map<String, TemplateString> parseStringMap(JsonNode node, String path, List<FieldError> errors) {
        Map<String, TemplateString> result = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return result;
        }
        if (!node.isObject()) {
            errors.add(new FieldError(path, "Nesne olmalı"));
            return result;
        }
        for (Map.Entry<String, JsonNode> entry : Json.fields(node)) {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if (key.isBlank()) {
                errors.add(new FieldError(path, "Ad boş olamaz"));
                continue;
            }
            if (value.isString()) {
                result.put(key, TemplateString.parse(value.stringValue()));
            } else if (value.isNumber() || value.isBoolean()) {
                result.put(key, TemplateString.parse(Json.text(value)));
            } else {
                errors.add(new FieldError(path + "." + key, "Metin, sayı ya da boolean olmalı"));
            }
        }
        return result;
    }

    private static BodySpec parseBody(JsonNode node, List<FieldError> errors) {
        if (node == null || node.isNull()) {
            return BodySpec.none();
        }
        if (!node.isObject()) {
            errors.add(new FieldError("request.body", "Nesne olmalı"));
            return BodySpec.none();
        }
        unknownKeys(node, BODY_KEYS, "request.body", errors);
        String modeName = Json.stringField(node, "mode");
        BodyMode mode = null;
        if (modeName != null) {
            for (BodyMode candidate : BodyMode.values()) {
                if (candidate.name().equals(modeName)) {
                    mode = candidate;
                }
            }
        }
        if (mode == null) {
            errors.add(new FieldError("request.body.mode", "NONE, PASS_THROUGH, TEMPLATE ya da MULTIPART olmalı"));
            return BodySpec.none();
        }
        JsonNode contentTypeNode = node.get("contentType");
        String contentType = null;
        if (contentTypeNode != null && !contentTypeNode.isNull()) {
            if (contentTypeNode.isString() && !contentTypeNode.stringValue().isBlank()) {
                contentType = contentTypeNode.stringValue();
            } else {
                errors.add(new FieldError("request.body.contentType", "Metin olmalı"));
            }
        }
        JsonNode template = node.get("template");
        JsonNode parts = node.get("parts");
        if (mode == BodyMode.TEMPLATE) {
            if (template == null || template.isNull()) {
                errors.add(new FieldError("request.body.template", "TEMPLATE modunda şablon zorunlu"));
            }
        } else if (template != null && !template.isNull()) {
            errors.add(new FieldError("request.body.template", "Şablon yalnızca TEMPLATE modunda kullanılır"));
        }
        List<PartSpec> partSpecs = new ArrayList<>();
        if (mode == BodyMode.MULTIPART) {
            if (parts == null || !parts.isArray() || parts.size() == 0) {
                errors.add(new FieldError("request.body.parts", "MULTIPART modunda en az bir parça zorunlu"));
            } else {
                partSpecs = parseParts(parts, errors);
            }
        } else if (parts != null && !parts.isNull()) {
            errors.add(new FieldError("request.body.parts", "Parçalar yalnızca MULTIPART modunda kullanılır"));
        }
        JsonTemplate compiled = mode == BodyMode.TEMPLATE && template != null ? JsonTemplate.compile(template) : null;
        return new BodySpec(mode, contentType, mode == BodyMode.TEMPLATE ? template : null, compiled, partSpecs);
    }

    private static List<PartSpec> parseParts(JsonNode parts, List<FieldError> errors) {
        List<PartSpec> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        int index = 0;
        for (JsonNode part : parts) {
            String path = "request.body.parts[" + index + "]";
            index++;
            if (!part.isObject()) {
                errors.add(new FieldError(path, "Nesne olmalı"));
                continue;
            }
            unknownKeys(part, PART_KEYS, path, errors);
            String name = Json.stringField(part, "name");
            String value = Json.stringField(part, "value");
            boolean file = booleanValue(part.get("file"), path + ".file", false, errors);
            if (name == null || name.isBlank()) {
                errors.add(new FieldError(path + ".name", "Parça adı zorunlu"));
                continue;
            }
            if (!names.add(name)) {
                errors.add(new FieldError(path + ".name", "Parça iki kez tanımlanmış: " + name));
                continue;
            }
            if (value == null) {
                errors.add(new FieldError(path + ".value", "Parça değeri metin olmalı"));
                continue;
            }
            result.add(new PartSpec(name, TemplateString.parse(value), file));
        }
        return result;
    }

    private static ResponseType parseResponse(JsonNode node, List<FieldError> errors) {
        if (node == null || node.isNull()) {
            return ResponseType.JSON;
        }
        if (!node.isObject()) {
            errors.add(new FieldError("response", "Nesne olmalı"));
            return ResponseType.JSON;
        }
        unknownKeys(node, RESPONSE_KEYS, "response", errors);
        JsonNode type = node.get("type");
        if (type == null || type.isNull()) {
            return ResponseType.JSON;
        }
        for (ResponseType candidate : ResponseType.values()) {
            if (type.isString() && candidate.name().equals(type.stringValue())) {
                return candidate;
            }
        }
        errors.add(new FieldError("response.type", "JSON, TEXT ya da BINARY olmalı"));
        return ResponseType.JSON;
    }

    private static AlarmSpec parseAlarm(JsonNode node, List<FieldError> errors) {
        if (node == null || node.isNull()) {
            return AlarmSpec.inherit();
        }
        if (!node.isObject()) {
            errors.add(new FieldError("alarm", "Nesne olmalı"));
            return AlarmSpec.inherit();
        }
        unknownKeys(node, ALARM_KEYS, "alarm", errors);
        AlarmSpec.Check check = AlarmSpec.Check.INHERIT;
        JsonNode value = node.get("check");
        if (value != null && !value.isNull()) {
            check = null;
            for (AlarmSpec.Check candidate : AlarmSpec.Check.values()) {
                if (value.isString() && candidate.name().equals(value.stringValue())) {
                    check = candidate;
                }
            }
            if (check == null) {
                errors.add(new FieldError("alarm.check", "INHERIT, OFF ya da ROUTE olmalı"));
                check = AlarmSpec.Check.INHERIT;
            }
        }
        return new AlarmSpec(check, text(node.get("maintask"), "alarm.maintask", errors),
                text(node.get("subtask"), "alarm.subtask", errors));
    }

    private static AlarmServiceSpec parseAlarmService(JsonNode node, List<FieldError> errors) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            errors.add(new FieldError("alarmService", "Nesne olmalı"));
            return null;
        }
        unknownKeys(node, ALARM_SERVICE_KEYS, "alarmService", errors);
        String resultPath = text(node.get("resultPath"), "alarmService.resultPath", errors);
        if (resultPath != null && !resultPath.isEmpty() && !FIELD_NAME.matcher(resultPath).matches()) {
            errors.add(new FieldError("alarmService.resultPath", "Noktalı bir JSON yolu olmalı, ör. data.alarms"));
        }
        return new AlarmServiceSpec(resultPath == null || resultPath.isEmpty() ? null : resultPath);
    }

    /** A list of plugin names, without repeats (ignoring case). */
    private static List<String> parseNames(JsonNode node, String path, List<FieldError> errors) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            errors.add(new FieldError(path, "Eklenti adlarının dizisi olmalı"));
            return List.of();
        }
        List<String> names = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int index = 0;
        for (JsonNode element : node) {
            String name = Json.isString(element) ? element.stringValue() : null;
            if (!Names.isValidProfileName(name)) {
                errors.add(new FieldError(path + "[" + index + "]", "Geçerli bir eklenti adı olmalı"));
            } else if (!seen.add(Names.key(name))) {
                errors.add(new FieldError(path + "[" + index + "]", "Aynı eklenti iki kez yazılmış: " + name));
            } else {
                names.add(name);
            }
            index++;
        }
        return List.copyOf(names);
    }

    private static String text(JsonNode node, String path, List<FieldError> errors) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            errors.add(new FieldError(path, "Metin olmalı"));
            return null;
        }
        return node.stringValue();
    }

    private static int parseRetry(JsonNode node, List<FieldError> errors) {
        if (node == null || node.isNull()) {
            return 1;
        }
        if (!node.isObject()) {
            errors.add(new FieldError("retry", "Nesne olmalı"));
            return 1;
        }
        unknownKeys(node, RETRY_KEYS, "retry", errors);
        return intValue(node.get("maxAttempts"), "retry.maxAttempts", 1, 1, RouteDefinition.MAX_ATTEMPTS_LIMIT, errors);
    }

    private static LoggingSpec parseLogging(JsonNode node, List<FieldError> errors) {
        LoggingSpec defaults = LoggingSpec.defaults();
        if (node == null || node.isNull()) {
            return defaults;
        }
        if (!node.isObject()) {
            errors.add(new FieldError("logging", "Nesne olmalı"));
            return defaults;
        }
        unknownKeys(node, LOGGING_KEYS, "logging", errors);
        boolean logRequestBody = booleanValue(node.get("logRequestBody"), "logging.logRequestBody",
                defaults.logRequestBody(), errors);
        boolean logResponseBody = booleanValue(node.get("logResponseBody"), "logging.logResponseBody",
                defaults.logResponseBody(), errors);
        List<String> maskFields = new ArrayList<>();
        JsonNode mask = node.get("maskFields");
        if (mask != null && !mask.isNull()) {
            if (!mask.isArray()) {
                errors.add(new FieldError("logging.maskFields", "Liste olmalı"));
            } else {
                int index = 0;
                for (JsonNode element : mask) {
                    if (element.isString() && !element.stringValue().isBlank()) {
                        maskFields.add(element.stringValue());
                    } else {
                        errors.add(new FieldError("logging.maskFields[" + index + "]", "Alan adı metin olmalı"));
                    }
                    index++;
                }
            }
        }
        int maxBodyBytes = intValue(node.get("maxBodyBytes"), "logging.maxBodyBytes",
                LoggingSpec.DEFAULT_MAX_BODY_BYTES, 1, LoggingSpec.MAX_BODY_BYTES_LIMIT, errors);
        String binaryInfo = null;
        JsonNode info = node.get("binaryInfo");
        if (info != null && !info.isNull()) {
            if (info.isString()) {
                binaryInfo = info.stringValue();
            } else {
                errors.add(new FieldError("logging.binaryInfo", "Metin olmalı"));
            }
        }
        return new LoggingSpec(logRequestBody, logResponseBody, List.copyOf(maskFields), maxBodyBytes, binaryInfo);
    }

    private static void unknownKeys(JsonNode node, Set<String> allowed, String path, List<FieldError> errors) {
        for (String name : Json.fieldNames(node)) {
            if (!allowed.contains(name)) {
                errors.add(new FieldError(path.isEmpty() ? name : path + "." + name, "Bilinmeyen alan"));
            }
        }
    }

    private static boolean booleanValue(JsonNode node, String path, boolean fallback, List<FieldError> errors) {
        if (node == null || node.isNull()) {
            return fallback;
        }
        if (!node.isBoolean()) {
            errors.add(new FieldError(path, "true ya da false olmalı"));
            return fallback;
        }
        return node.booleanValue();
    }

    private static int intValue(JsonNode node, String path, int fallback, int min, int max, List<FieldError> errors) {
        if (node == null || node.isNull()) {
            return fallback;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            errors.add(new FieldError(path, "Tam sayı olmalı"));
            return fallback;
        }
        int value = node.intValue();
        if (value < min || value > max) {
            errors.add(new FieldError(path, min + " ile " + max + " arasında olmalı"));
            return fallback;
        }
        return value;
    }
}
