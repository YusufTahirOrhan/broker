package com.acme.broker.runtime.route;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import com.acme.broker.core.response.ResponseProfile;
import com.acme.broker.core.route.HttpMethodName;
import com.acme.broker.core.route.PathTemplate;
import com.acme.broker.core.route.RouteDefinitionParser;
import com.acme.broker.core.route.RouteValidator;
import com.acme.broker.core.validation.FieldError;

import tools.jackson.databind.JsonNode;

/** Turns stored rows into a {@link Route} or a {@link CompiledProfile}, reporting why a row cannot be served. */
public final class RouteCompiler {

    private RouteCompiler() {
    }

    public sealed interface Compiled<T> {
        record Ok<T>(T value) implements Compiled<T> {
        }

        record Broken<T>(String reason) implements Compiled<T> {
        }
    }

    public static Compiled<CompiledProfile> profile(String name, int version, JsonNode definition) {
        ResponseProfile.Parsed parsed = ResponseProfile.parse(definition);
        if (!parsed.valid()) {
            return new Compiled.Broken<>(describe(parsed.errors()));
        }
        return new Compiled.Ok<>(new CompiledProfile(name, version, parsed.profile()));
    }

    public static Compiled<Route> route(String maintask, String subtask, int version, String httpMethod,
                                        String pathTemplate, List<String> permissions, boolean anonymous,
                                        CompiledProfile profile, JsonNode definition, String baseUrl,
                                        boolean skipPermissionCheck) {
        List<FieldError> errors = new ArrayList<>(
                RouteValidator.validate(httpMethod, pathTemplate, definition, RouteValidator.Context.anyHeader()));
        if (!anonymous && (permissions == null || permissions.isEmpty()) && !skipPermissionCheck) {
            errors.add(new FieldError("permissions", "Anonim olmayan route yetkisiz olamaz"));
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            errors.add(new FieldError("baseUrl", "Projenin base URL'i yok"));
        }
        if (!errors.isEmpty()) {
            return new Compiled.Broken<>(describe(errors));
        }
        return new Compiled.Ok<>(new Route(maintask, subtask, version, HttpMethodName.parse(httpMethod).orElseThrow(),
                PathTemplate.parse(pathTemplate),
                permissions == null ? List.of() : List.copyOf(new LinkedHashSet<>(permissions)), anonymous,
                profile, RouteDefinitionParser.parse(definition).definition(), baseUrl, skipPermissionCheck));
    }

    private static String describe(List<FieldError> errors) {
        return errors.stream().map(error -> error.field() + ": " + error.message()).reduce((a, b) -> a + "; " + b)
                .orElse("");
    }
}
