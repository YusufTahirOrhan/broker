package com.acme.yapi.admin.local;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.acme.yapi.admin.environment.Environment;
import com.acme.yapi.admin.environment.EnvironmentRepository;
import com.acme.yapi.admin.profile.ProfileService;
import com.acme.yapi.admin.project.ProjectRepository;
import com.acme.yapi.admin.project.ProjectService;
import com.acme.yapi.admin.route.RouteService;
import com.acme.yapi.admin.route.RouteSnapshots;
import com.acme.yapi.admin.security.Role;
import com.acme.yapi.admin.security.User;
import com.acme.yapi.core.json.Json;

import tools.jackson.databind.JsonNode;

/**
 * Sample data for a developer's empty central DB ({@code local} profile, {@code yapi.admin.local.seed: true}):
 * {@code local-seed.json} written through the same services the API uses, so histories and checks are real. Base
 * URLs are written to the central DB only; the environments are not called. A DB with any project is left alone.
 */
@Component
@Profile("local")
@ConditionalOnProperty(name = "yapi.admin.local.seed", havingValue = "true")
public class LocalSeed implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalSeed.class);
    private static final String USER = "seed";

    private final ProjectService projectService;
    private final ProjectRepository projects;
    private final ProfileService profiles;
    private final RouteService routes;
    private final EnvironmentRepository environments;

    public LocalSeed(ProjectService projectService, ProjectRepository projects, ProfileService profiles,
                     RouteService routes, EnvironmentRepository environments) {
        this.projectService = projectService;
        this.projects = projects;
        this.profiles = profiles;
        this.routes = routes;
        this.environments = environments;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (!projects.all().isEmpty()) {
            return;
        }
        JsonNode seed;
        try (InputStream in = new ClassPathResource("local-seed.json").getInputStream()) {
            seed = Json.parse(in.readAllBytes());
        }
        User user = User.of(USER, "Tohum verisi", EnumSet.of(Role.ROUTE_EDITOR, Role.APPROVER));
        Map<String, Long> profileIds = new HashMap<>();
        for (JsonNode profile : seed.get("profiles")) {
            long id = profiles.create(USER, new ProfileService.Body(Json.stringField(profile, "name"),
                    Json.stringField(profile, "description"), profile.get("definition"), null, "Tohum verisi")).id();
            profileIds.put(Json.stringField(profile, "name"), id);
        }
        int routeCount = 0;
        for (JsonNode project : seed.get("projects")) {
            String defaultProfile = Json.stringField(project, "defaultResponseProfile");
            long id = projectService.register(user, new ProjectService.Register(
                    Json.stringField(project, "externalProjectId"), Json.stringField(project, "maintask"), null,
                    Json.stringField(project, "permissionPrefix"), Json.stringField(project, "ownerTeam"),
                    Json.stringField(project, "description"),
                    defaultProfile == null ? null : profileIds.get(defaultProfile))).id();
            for (Environment environment : environments.all()) {
                projects.upsertEnvironment(id, environment.code(), Json.stringField(project, "baseUrl"),
                        "/v3/api-docs", true, null);
            }
            for (JsonNode route : project.get("routes")) {
                String profile = Json.stringField(route, "responseProfile");
                boolean anonymous = route.has("anonymous") && route.get("anonymous").booleanValue();
                routes.create(USER, id, new RouteService.Body(id, Json.stringField(route, "subtask"),
                        Json.stringField(route, "httpMethod"), Json.stringField(route, "pathTemplate"),
                        RouteSnapshots.strings(route.get("permissions")), List.of(), anonymous,
                        profile == null ? null : profileIds.get(profile), route.get("definition"), null, null,
                        "Tohum verisi"));
                routeCount++;
            }
        }
        log.info("Yerel tohum verisi yazıldı: {} proje, {} subtask, {} profil", seed.get("projects").size(),
                routeCount, profileIds.size());
    }
}
