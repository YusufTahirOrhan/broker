package com.acme.yapi.runtime.route;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.naming.Names;
import com.acme.yapi.runtime.config.YapiProperties;

import tools.jackson.databind.JsonNode;

/**
 * {@code yapi-local.json} on top of DEV's routes, only in the {@code local} profile: {@code baseUrls} sends a
 * project's calls to the developer's machine, {@code routes} adds or replaces subtasks in the central definition's
 * format. The file is watched; a change needs no restart. This bean never exists in DEV, TEST or PROD.
 */
@Component
@Profile("local")
public class LocalOverrides implements SnapshotCustomizer {

    private static final Logger log = LoggerFactory.getLogger(LocalOverrides.class);

    private final Path file;
    private final boolean skipPermissionCheck;
    private volatile long readModified = Long.MIN_VALUE;

    public LocalOverrides(YapiProperties properties, ResourceLoader resources) throws IOException {
        String location = properties.localOverrides() == null ? "file:./yapi-local.json" : properties.localOverrides();
        Resource resource = resources.getResource(location);
        this.file = resource.getFile().toPath();
        this.skipPermissionCheck = properties.local().skipPermissionCheckForOverrides();
    }

    @Override
    public boolean changed() {
        return modified() != readModified;
    }

    @Override
    public RouteSnapshot customize(RouteSnapshot loaded) {
        long modified = modified();
        readModified = modified;
        if (modified == Long.MIN_VALUE) {
            return loaded;
        }
        JsonNode overrides;
        try {
            overrides = Json.tryParse(Files.readAllBytes(file));
        } catch (IOException e) {
            log.warn("Override dosyası okunamadı: {}", file, e);
            return loaded;
        }
        if (overrides == null || !overrides.isObject()) {
            log.warn("Override dosyası bir JSON nesnesi değil: {}", file);
            return loaded;
        }
        Map<String, String> baseUrls = new HashMap<>();
        for (Map.Entry<String, JsonNode> entry : Json.fields(overrides.get("baseUrls"))) {
            baseUrls.put(Names.key(entry.getKey()), Json.text(entry.getValue()));
        }
        Map<String, RouteSnapshot.Project> projects = new LinkedHashMap<>();
        for (RouteSnapshot.Project project : loaded.projects()) {
            String url = baseUrls.getOrDefault(Names.key(project.maintask()), project.baseUrl());
            projects.put(Names.key(project.maintask()), new RouteSnapshot.Project(project.maintask(), url,
                    project.enabled()));
        }
        Map<String, Route> routes = new LinkedHashMap<>();
        for (Route route : loaded.routes()) {
            String url = baseUrls.get(Names.key(route.maintask()));
            routes.put(RouteSnapshot.key(route.maintask(), route.subtask()), url == null ? route : route.withBaseUrl(url));
        }
        int added = 0;
        for (JsonNode node : overrides.path("routes")) {
            Route route = overrideRoute(node, loaded, baseUrls, projects);
            if (route != null) {
                routes.put(RouteSnapshot.key(route.maintask(), route.subtask()), route);
                projects.putIfAbsent(Names.key(route.maintask()),
                        new RouteSnapshot.Project(route.maintask(), route.baseUrl(), true));
                added++;
            }
        }
        log.info("Yerel override uygulandı: {} base URL, {} route", baseUrls.size(), added);
        return RouteSnapshot.of(loaded.version(), routes.values(), projects.values(), loaded.profiles());
    }

    private Route overrideRoute(JsonNode node, RouteSnapshot loaded, Map<String, String> baseUrls,
                                Map<String, RouteSnapshot.Project> projects) {
        String maintask = Json.stringField(node, "maintask");
        String subtask = Json.stringField(node, "subtask");
        if (maintask == null || subtask == null) {
            log.warn("Override route'unda maintask ya da subtask yok: {}", node);
            return null;
        }
        String baseUrl = baseUrls.get(Names.key(maintask));
        if (baseUrl == null && projects.containsKey(Names.key(maintask))) {
            baseUrl = projects.get(Names.key(maintask)).baseUrl();
        }
        CompiledProfile profile = null;
        String profileName = Json.stringField(node, "responseProfile");
        if (profileName != null) {
            profile = loaded.profile(profileName).orElse(null);
            if (profile == null) {
                log.warn("Override route'u {}/{} atlandı: profil DEV'de yok: {}", maintask, subtask, profileName);
                return null;
            }
        }
        List<String> permissions = new ArrayList<>();
        for (JsonNode permission : node.path("permissions")) {
            permissions.add(Json.text(permission));
        }
        JsonNode anonymous = node.get("anonymous");
        return switch (RouteCompiler.route(maintask, subtask, 0, Json.stringField(node, "httpMethod"),
                Json.stringField(node, "pathTemplate"), permissions, anonymous != null && anonymous.booleanValue(),
                profile, node.get("definition"), baseUrl, skipPermissionCheck)) {
            case RouteCompiler.Compiled.Ok<Route> ok -> ok.value();
            case RouteCompiler.Compiled.Broken<Route> broken -> {
                log.warn("Override route'u {}/{} atlandı: {}", maintask, subtask, broken.reason());
                yield null;
            }
        };
    }

    private long modified() {
        try {
            return Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : Long.MIN_VALUE;
        } catch (IOException e) {
            return Long.MIN_VALUE;
        }
    }
}
