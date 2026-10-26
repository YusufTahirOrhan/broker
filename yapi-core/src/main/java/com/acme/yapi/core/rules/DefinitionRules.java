package com.acme.yapi.core.rules;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.naming.Names;
import com.acme.yapi.core.route.BodyMode;
import com.acme.yapi.core.route.FieldType;
import com.acme.yapi.core.route.HttpMethodName;
import com.acme.yapi.core.route.LoggingSpec;
import com.acme.yapi.core.route.ResponseType;
import com.acme.yapi.core.route.RouteDefinition;
import com.acme.yapi.core.route.RouteDefinitionParser;
import com.acme.yapi.core.route.RouteValidator;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** The rules screens read from {@code GET /api/v1/meta/definition-rules} instead of hard-coding them. */
public final class DefinitionRules {

    private DefinitionRules() {
    }

    public static ObjectNode describe(Collection<String> readableHeaders) {
        ObjectNode rules = Json.object();
        strings(rules.putArray("httpMethods"), Arrays.stream(HttpMethodName.values()).map(Enum::name).toList());
        strings(rules.putArray("fieldTypes"), Arrays.stream(FieldType.values()).map(FieldType::jsonName).toList());
        strings(rules.putArray("bodyModes"), Arrays.stream(BodyMode.values()).map(Enum::name).toList());
        strings(rules.putArray("responseTypes"), Arrays.stream(ResponseType.values()).map(Enum::name).toList());
        strings(rules.putArray("envelopeFields"), RouteDefinitionParser.ENVELOPE_FIELDS.stream().sorted().toList());
        strings(rules.putArray("readableHeaders"), List.copyOf(readableHeaders));
        strings(rules.putArray("forbiddenHeaders"), RouteValidator.FORBIDDEN_HEADERS);
        strings(rules.putArray("forbiddenHeaderExceptions"), RouteValidator.FORBIDDEN_HEADER_EXCEPTIONS);
        strings(rules.putArray("alwaysMaskedFields"), List.of(LoggingSpec.ALWAYS_MASKED));

        ObjectNode timeout = rules.putObject("timeoutMs");
        timeout.put("min", RouteDefinition.MIN_TIMEOUT_MS);
        timeout.put("max", RouteDefinition.MAX_TIMEOUT_MS);
        timeout.put("default", RouteDefinition.DEFAULT_TIMEOUT_MS);
        ObjectNode retry = rules.putObject("retryMaxAttempts");
        retry.put("min", 1);
        retry.put("max", RouteDefinition.MAX_ATTEMPTS_LIMIT);
        retry.put("default", 1);
        retry.put("onlyFor", HttpMethodName.GET.name());
        ObjectNode maxBody = rules.putObject("maxBodyBytes");
        maxBody.put("min", 1);
        maxBody.put("max", LoggingSpec.MAX_BODY_BYTES_LIMIT);
        maxBody.put("default", LoggingSpec.DEFAULT_MAX_BODY_BYTES);

        ObjectNode patterns = rules.putObject("patterns");
        patterns.put("maintask", Names.MAINTASK.pattern());
        patterns.put("subtask", Names.SUBTASK.pattern());
        patterns.put("permissionPrefix", Names.PERMISSION_PREFIX.pattern());
        patterns.put("permissionSuffix", Names.PERMISSION_SUFFIX.pattern());
        patterns.put("profileName", Names.PROFILE_NAME.pattern());
        ObjectNode lengths = rules.putObject("maxLengths");
        lengths.put("subtask", Names.SUBTASK_MAX_LENGTH);
        lengths.put("permissionPrefix", Names.PERMISSION_PREFIX_MAX_LENGTH);
        lengths.put("permission", Names.PERMISSION_MAX_LENGTH);
        lengths.put("pathTemplate", 512);

        ObjectNode placeholders = rules.putObject("placeholders");
        strings(placeholders.putArray("definition"),
                List.of("${request.<yol>}", "${request.uid}", "${request.application}", "${request.maintask}",
                        "${request.subtask}", "${header.<ad>}", "${user.id}", "${user.permission}",
                        "${requestId}"));
        strings(placeholders.putArray("profile"),
                List.of("${backend.status}", "${backend.body}", "${outcome}", "${requestId}"));
        return rules;
    }

    private static void strings(ArrayNode array, List<String> values) {
        values.forEach(array::add);
    }
}
