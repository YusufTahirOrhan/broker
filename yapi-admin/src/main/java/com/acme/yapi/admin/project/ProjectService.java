package com.acme.yapi.admin.project;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.acme.yapi.admin.authz.AuthorizationService;
import com.acme.yapi.admin.catalog.ProjectCatalog;
import com.acme.yapi.admin.environment.Environment;
import com.acme.yapi.admin.environment.EnvironmentClient;
import com.acme.yapi.admin.environment.EnvironmentRepository;
import com.acme.yapi.admin.profile.ProfileRepository;
import com.acme.yapi.admin.route.RouteRepository;
import com.acme.yapi.admin.security.Role;
import com.acme.yapi.admin.security.User;
import com.acme.yapi.admin.web.ApiException;
import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.naming.Names;
import com.acme.yapi.core.route.AlarmRoute;
import com.acme.yapi.core.route.RouteDefinitionParser;
import com.acme.yapi.core.validation.FieldError;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class ProjectService {

    static final String DEFAULT_SPEC_PATH = "/v3/api-docs";

    private final ProjectRepository projects;
    private final ProfileRepository profiles;
    private final ProjectCatalog catalog;
    private final AuthorizationService authorization;
    private final EnvironmentRepository environments;
    private final EnvironmentClient environmentClient;
    private final RouteRepository routes;

    public ProjectService(ProjectRepository projects, ProfileRepository profiles, ProjectCatalog catalog,
                          AuthorizationService authorization, EnvironmentRepository environments,
                          EnvironmentClient environmentClient, RouteRepository routes) {
        this.projects = projects;
        this.profiles = profiles;
        this.catalog = catalog;
        this.authorization = authorization;
        this.environments = environments;
        this.environmentClient = environmentClient;
        this.routes = routes;
    }

    public record ProfileRef(long id, String name) {
    }

    public record EnvironmentView(String environment, String baseUrl, String specPath, boolean enabled,
                                  AlarmRoute alarmRoute) {
    }

    public record View(long id, String externalProjectId, String maintask, String name, String permissionPrefix,
                       String catalogStatus, Instant catalogSyncedAt, String ownerTeam, String description,
                       ProfileRef defaultResponseProfile, String createdBy, Instant createdAt,
                       List<EnvironmentView> environments) {
    }

    public record CatalogView(String externalProjectId, String name, Long registeredProjectId) {
    }

    public record Register(String externalProjectId, String maintask, String name, String permissionPrefix,
                           String ownerTeam, String description, Long defaultResponseProfileId) {
    }

    /** @param alarmRoute the alarm service route of the subtasks whose alarm check is {@code INHERIT}; null: none */
    public record EnvironmentSettings(String baseUrl, String specPath, Boolean enabled, AlarmRoute alarmRoute) {
    }

    public record EnvironmentResult(String environment, String baseUrl, String specPath, boolean enabled,
                                    AlarmRoute alarmRoute, Long configVersion) {
    }

    public List<CatalogView> searchCatalog(String query) {
        Map<String, Long> registered = projects.registeredByExternalId();
        return catalog.search(query).stream()
                .map(project -> new CatalogView(project.id(), project.name(), registered.get(project.id())))
                .toList();
    }

    public List<View> list(String query, String catalogStatus) {
        return projects.list(query, catalogStatus).stream().map(this::view).toList();
    }

    public View get(long id) {
        return view(require(id));
    }

    @Transactional
    public View register(User user, Register body) {
        List<FieldError> errors = new ArrayList<>();
        if (!Names.isValidMaintask(body.maintask())) {
            errors.add(new FieldError("maintask", "1–100 karakter; harf ya da rakamla başlar, harf, rakam, _, . ve - içerir"));
        }
        if (!Names.isValidPrefix(body.permissionPrefix())) {
            errors.add(new FieldError("permissionPrefix", "Büyük harf, rakam ve _ ile en fazla 50 karakter, ör. LOGPROJECT"));
        }
        String name = body.name();
        if (body.externalProjectId() != null) {
            Optional<ProjectCatalog.CatalogProject> found = catalog.find(body.externalProjectId());
            if (found.isEmpty()) {
                errors.add(new FieldError("externalProjectId", "Koordinatör'de yok"));
            } else if (name == null || name.isBlank()) {
                name = found.get().name();
            }
        }
        if (name == null || name.isBlank() || name.length() > 200) {
            errors.add(new FieldError("name", "Görünen ad zorunlu, en fazla 200 karakter"));
        }
        if (body.defaultResponseProfileId() != null && profiles.find(body.defaultResponseProfileId()).isEmpty()) {
            errors.add(new FieldError("defaultResponseProfileId", "Profil yok"));
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation("Proje", errors);
        }
        for (String prefix : projects.prefixes()) {
            if (prefix.equals(body.permissionPrefix())) {
                throw ApiException.conflict("DUPLICATE", "Bu yetki öneki zaten kayıtlı: " + prefix);
            }
            if (Names.prefixesOverlap(prefix, body.permissionPrefix())) {
                throw ApiException.conflict("PREFIX_OVERLAP",
                        "Yetki öneki " + prefix + " önekiyle çakışıyor; iki projenin yetkileri karışır.");
            }
        }
        try {
            long id = projects.insert(body.externalProjectId(), body.maintask(), name.strip(), body.permissionPrefix(),
                    body.ownerTeam(), body.description(), body.defaultResponseProfileId(), user.username());
            return get(id);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("DUPLICATE",
                    "maintask ya da Koordinatör proje ID'si zaten kayıtlı (büyük/küçük harf fark etmez).");
        }
    }

    /** Only the fields present in the body change; {@code externalProjectId} only links an UNLINKED project. */
    @Transactional
    public View patch(User user, long id, JsonNode body) {
        ProjectRepository.Row project = require(id);
        if (body == null || !body.isObject()) {
            throw ApiException.validation(List.of(new FieldError("", "Gövde bir JSON nesnesi olmalı")));
        }
        List<FieldError> errors = new ArrayList<>();
        for (String field : List.of("maintask", "permissionPrefix")) {
            if (body.has(field) && !Json.text(body.get(field)).equals(field.equals("maintask")
                    ? project.maintask() : project.permissionPrefix())) {
                errors.add(new FieldError(field, "Kayıttan sonra değiştirilemez"));
            }
        }
        String name = body.has("name") ? Json.text(body.get("name")) : project.name();
        if (name == null || name.isBlank() || name.length() > 200) {
            errors.add(new FieldError("name", "Görünen ad zorunlu, en fazla 200 karakter"));
        }
        String ownerTeam = body.has("ownerTeam") ? Json.text(body.get("ownerTeam")) : project.ownerTeam();
        String description = body.has("description") ? Json.text(body.get("description")) : project.description();
        Long profileId = project.defaultResponseProfileId();
        if (body.has("defaultResponseProfileId")) {
            JsonNode value = body.get("defaultResponseProfileId");
            profileId = value.isNull() ? null : value.longValue();
            if (profileId != null && profiles.find(profileId).isEmpty()) {
                errors.add(new FieldError("defaultResponseProfileId", "Profil yok"));
            }
        }
        String externalId = body.has("externalProjectId") ? Json.text(body.get("externalProjectId")) : null;
        ProjectCatalog.CatalogProject linked = null;
        if (externalId != null && !externalId.equals(project.externalProjectId())) {
            if (!"UNLINKED".equals(project.catalogStatus())) {
                errors.add(new FieldError("externalProjectId", "Koordinatör proje ID'si kayıttan sonra değiştirilemez"));
            } else {
                linked = catalog.find(externalId).orElse(null);
                if (linked == null) {
                    errors.add(new FieldError("externalProjectId", "Koordinatör'de yok"));
                }
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation("Proje", errors);
        }
        projects.update(id, name.strip(), ownerTeam, description, profileId);
        if (linked != null) {
            try {
                projects.catalog(id, linked.id(), body.has("name") ? name.strip() : linked.name(), "LINKED");
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("DUPLICATE", "Bu Koordinatör proje ID'si başka bir projeye bağlı.");
            }
        }
        return get(id);
    }

    /**
     * Base URL, spec path and switch of one environment. Applied to the environment first: if its Admin API cannot
     * be reached nothing changes here either. Environments that require approval accept this only from APPROVER.
     */
    public EnvironmentResult putEnvironment(User user, long id, String code, EnvironmentSettings body) {
        ProjectRepository.Row project = require(id);
        Environment environment = environments.require(code);
        user.require(environment.requiresApproval() ? Role.APPROVER : Role.ROUTE_EDITOR);
        List<FieldError> errors = new ArrayList<>();
        baseUrlProblems(body.baseUrl()).forEach(problem -> errors.add(new FieldError("baseUrl", problem)));
        String specPath = body.specPath() == null || body.specPath().isBlank() ? DEFAULT_SPEC_PATH : body.specPath();
        if (!specPath.startsWith("/") || specPath.length() > 256 || specPath.contains("..")) {
            errors.add(new FieldError("specPath", "/ ile başlayan bir yol olmalı, en fazla 256 karakter"));
        }
        AlarmRoute alarm = alarmRoute(body.alarmRoute(), errors);
        if (!errors.isEmpty()) {
            throw ApiException.validation("Ortam ayarı", errors);
        }
        boolean enabled = body.enabled() == null || body.enabled();
        ObjectNode request = Json.object();
        request.put("baseUrl", body.baseUrl());
        request.put("enabled", enabled);
        if (alarm == null) {
            request.putNull("alarmRoute");
        } else {
            request.putObject("alarmRoute").put("maintask", alarm.maintask()).put("subtask", alarm.subtask());
        }
        request.put("actor", user.username());
        JsonNode applied = environmentClient.call(environment, "PUT", "/admin/v1/projects/" + project.maintask(), request);
        projects.upsertEnvironment(id, code, body.baseUrl(), specPath, enabled, alarm);
        JsonNode version = applied == null ? null : applied.get("configVersion");
        return new EnvironmentResult(code, body.baseUrl(), specPath, enabled, alarm,
                version == null || version.isNull() ? null : version.longValue());
    }

    /**
     * The registered spelling of an alarm route, which must be a subtask whose current version is an alarm service
     * route. Whether it is active in the environment is checked when a subtask that uses it is promoted.
     */
    private AlarmRoute alarmRoute(AlarmRoute requested, List<FieldError> errors) {
        if (requested == null) {
            return null;
        }
        if (!Names.isValidMaintask(requested.maintask()) || !Names.isValidSubtask(requested.subtask())) {
            errors.add(new FieldError("alarmRoute", "maintask ve subtask gerekli"));
            return null;
        }
        Optional<RouteRepository.Row> route = routes.find(requested.maintask(), requested.subtask());
        if (route.isEmpty()) {
            errors.add(new FieldError("alarmRoute", "Böyle bir subtask yok: " + requested.maintask() + "/"
                    + requested.subtask()));
            return null;
        }
        if (!RouteDefinitionParser.isAlarmService(route.get().definition())) {
            errors.add(new FieldError("alarmRoute", "Alarm servisi route'u değil: " + route.get().maintask() + "/"
                    + route.get().subtask()));
            return null;
        }
        return new AlarmRoute(route.get().maintask(), route.get().subtask());
    }

    /** @param suffix the code after the project's prefix; null when the Koordinatör's code does not start with it */
    public record PermissionView(String code, String suffix, String description, long routeCount) {
    }

    /**
     * The project's permissions in the environment's Koordinatör (its project's {@code yetkiList}) and how many
     * active subtasks use each. Permissions are opened in the Koordinatör, not here.
     */
    public List<PermissionView> permissions(long id, String environment) {
        ProjectRepository.Row project = require(id);
        String code = environment == null || environment.isBlank() ? environments.first().code() : environment;
        environments.require(code);
        List<AuthorizationService.Permission> permissions =
                authorization.list(code, project.externalProjectId(), project.permissionPrefix());
        Map<String, Long> usage = projects.permissionUsage(permissions.stream()
                .map(AuthorizationService.Permission::code).toList());
        String prefix = project.permissionPrefix();
        return permissions.stream()
                .map(permission -> new PermissionView(permission.code(),
                        Names.belongsTo(permission.code(), prefix)
                                ? permission.code().substring(prefix.length() + 1) : null,
                        permission.description(), usage.getOrDefault(permission.code(), 0L)))
                .toList();
    }

    public ProjectRepository.Row require(long id) {
        return projects.find(id).orElseThrow(() -> ApiException.notFound("Proje yok: " + id));
    }

    private View view(ProjectRepository.Row row) {
        return new View(row.id(), row.externalProjectId(), row.maintask(), row.name(), row.permissionPrefix(),
                row.catalogStatus(), row.catalogSyncedAt(), row.ownerTeam(), row.description(),
                row.defaultResponseProfileId() == null ? null
                        : new ProfileRef(row.defaultResponseProfileId(), row.defaultProfileName()),
                row.createdBy(), row.createdAt(),
                projects.environments(row.id()).stream()
                        .map(env -> new EnvironmentView(env.environment(), env.baseUrl(), env.specPath(), env.enabled(),
                                env.alarmRoute()))
                        .toList());
    }

    static List<String> baseUrlProblems(String baseUrl) {
        List<String> problems = new ArrayList<>();
        if (baseUrl == null || baseUrl.isBlank()) {
            problems.add("Base URL zorunlu");
            return problems;
        }
        try {
            URI uri = new URI(baseUrl);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null) {
                problems.add("http ya da https ile başlayan tam bir adres olmalı");
            }
            if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                problems.add("Kullanıcı bilgisi, query ya da fragment içeremez");
            }
        } catch (URISyntaxException e) {
            problems.add("Geçerli bir adres değil");
        }
        if (baseUrl.length() > 512) {
            problems.add("En fazla 512 karakter olabilir");
        }
        return problems;
    }
}
