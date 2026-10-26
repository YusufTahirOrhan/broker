package com.acme.yapi.admin.koordinator;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatus;

import com.acme.yapi.admin.config.KoordinatorProperties;
import com.acme.yapi.admin.web.ApiException;
import com.acme.yapi.core.json.Json;

import tools.jackson.databind.JsonNode;

/**
 * The Yapi projects of each environment's Koordinatör ({@code GET /api/koordinator/projeler-ve-yetkileri}, only the
 * ones with {@code is_yapi}) with their permissions, kept for {@code koordinator.cache-ttl} per environment. The
 * project catalog and the authorization service both read them. A failed call is not cached.
 */
public class KoordinatorProjects {

    /** @param id {@code projeId}; @param name {@code projeAdi}; @param permissions {@code yetkiList[].yetki} */
    public record Project(String id, String name, List<String> permissions) {
    }

    private record Loaded(List<Project> projects, Instant at) {
    }

    private final HttpClient client;
    private final KoordinatorProperties settings;
    private final Clock clock;
    private final Map<String, Loaded> cache = new ConcurrentHashMap<>();

    public KoordinatorProjects(KoordinatorProperties settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public List<Project> projects(String environment) {
        Loaded loaded = cache.get(environment);
        Instant now = clock.instant();
        if (loaded != null && loaded.at().plus(settings.cacheTtl()).isAfter(now)) {
            return loaded.projects();
        }
        List<Project> projects = load(environment);
        cache.put(environment, new Loaded(projects, now));
        return projects;
    }

    private List<Project> load(String environment) {
        HttpResponse<byte[]> response = get(environment);
        if (response.statusCode() != 200) {
            throw upstream(environment, response.statusCode() + " döndü");
        }
        JsonNode list = Json.tryParse(response.body());
        if (list == null || !list.isArray()) {
            throw upstream(environment, "proje listesi bir JSON dizisi değil");
        }
        List<Project> projects = new ArrayList<>();
        for (JsonNode project : list) {
            String id = Json.isAbsent(project.get(settings.projectIdField())) ? null
                    : Json.text(project.get(settings.projectIdField()));
            String name = Json.stringField(project, settings.projectNameField());
            if (id == null || name == null || !Json.isTrue(project.get(settings.yapiField()))) {
                continue;
            }
            LinkedHashSet<String> permissions = new LinkedHashSet<>();
            JsonNode permissionList = project.get(settings.permissionsField());
            if (permissionList != null && permissionList.isArray()) {
                for (JsonNode permission : permissionList) {
                    String code = Json.stringField(permission, settings.permissionCodeField());
                    if (code != null && !code.isBlank()) {
                        permissions.add(code);
                    }
                }
            }
            projects.add(new Project(id, name, List.copyOf(permissions)));
        }
        return List.copyOf(projects);
    }

    private HttpResponse<byte[]> get(String environment) {
        String url = settings.urls().get(environment);
        if (url == null || url.isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "UPSTREAM_ERROR",
                    environment + " için Koordinatör adresi tanımlı değil (koordinator.urls." + environment + ").");
        }
        HttpRequest request;
        try {
            request = settings.authorize(HttpRequest.newBuilder(URI.create(
                            (url.endsWith("/") ? url.substring(0, url.length() - 1) : url) + settings.projectsPath()))
                    .timeout(settings.timeout()).header("Accept", "application/json").GET()).build();
        } catch (IllegalArgumentException e) {
            throw upstream(environment, "adresi geçersiz");
        }
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw upstream(environment, "ulaşılamadı");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw upstream(environment, "çağrı kesildi");
        }
    }

    private static ApiException upstream(String environment, String what) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "UPSTREAM_ERROR", environment + " Koordinatör'ü: " + what + ".");
    }
}
