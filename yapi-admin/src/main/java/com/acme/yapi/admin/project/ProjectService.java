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
import com.acme.yapi.admin.security.Role;
import com.acme.yapi.admin.security.User;
import com.acme.yapi.admin.web.ApiException;
import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.naming.Names;
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

    public ProjectService(ProjectRepository projects, ProfileRepository profiles, ProjectCatalog catalog,
                          AuthorizationService authorization, EnvironmentRepository environments,
                          EnvironmentClient environmentClient) {
        this.projects = projects;
        this.profiles = profiles;
        this.catalog = catalog;
        this.authorization = authorization;
        this.environments = environments;
        this.environmentClient = environmentClient;
    }

    public record ProfileRef(long id, String name) {
    }

    public record EnvironmentView(String environment, String baseUrl, String specPath, boolean enabled) {
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

    public record EnvironmentSettings(String baseUrl, String specPath, Boolean enabled) {
    }

    public record EnvironmentResult(String environment, String baseUrl, String specPath, boolean enabled,
                                    Long configVersion) {
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
                errors.add(new FieldError("externalProjectId", "Proje servisinde yok"));
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
                    "maintask ya da proje servisi ID'si zaten kayıtlı (büyük/küçük harf fark etmez).");
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
                errors.add(new FieldError("externalProjectId", "Proje servisi ID'si kayıttan sonra değiştirilemez"));
            } else {
                linked = catalog.find(externalId).orElse(null);
                if (linked == null) {
                    errors.add(new FieldError("externalProjectId", "Proje servisinde yok"));
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
                throw ApiException.conflict("DUPLICATE", "Bu proje servisi ID'si başka bir projeye bağlı.");
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
        if (!errors.isEmpty()) {
            throw ApiException.validation("Ortam ayarı", errors);
        }
        boolean enabled = body.enabled() == null || body.enabled();
        ObjectNode request = Json.object();
        request.put("baseUrl", body.baseUrl());
        request.put("enabled", enabled);
        request.put("actor", user.username());
        JsonNode applied = environmentClient.call(environment, "PUT", "/admin/v1/projects/" + project.maintask(), request);
        projects.upsertEnvironment(id, code, body.baseUrl(), specPath, enabled);
        JsonNode version = applied == null ? null : applied.get("configVersion");
        return new EnvironmentResult(code, body.baseUrl(), specPath, enabled,
                version == null || version.isNull() ? null : version.longValue());
    }

    public record PermissionView(String code, String suffix, String description, long routeCount) {
    }

    public List<PermissionView> permissions(long id, String environment) {
        ProjectRepository.Row project = require(id);
        String code = environment == null || environment.isBlank() ? environments.first().code() : environment;
        environments.require(code);
        Map<String, Long> usage = projects.permissionUsage(project.permissionPrefix());
        return authorization.list(code, project.permissionPrefix()).stream()
                .map(permission -> new PermissionView(permission.code(),
                        permission.code().substring(project.permissionPrefix().length() + 1), permission.description(),
                        usage.getOrDefault(permission.code(), 0L)))
                .toList();
    }

    public record NewPermission(String suffix, String description, List<String> environments) {
    }

    public record PermissionResult(String environment, boolean created) {
    }

    public record CreatedPermission(String code, List<PermissionResult> environments) {
    }

    /** Opens {@code PREFIX_SUFFIX} in the authorization service of the given environments (all by default). */
    public CreatedPermission createPermission(long id, NewPermission body) {
        ProjectRepository.Row project = require(id);
        String suffix = body.suffix() == null ? null : body.suffix().toUpperCase(Locale.ROOT);
        List<FieldError> errors = new ArrayList<>();
        if (!Names.isValidSuffix(suffix)) {
            errors.add(new FieldError("suffix", "Büyük harf, rakam ve _ ile yazılır, ör. MODERATOR"));
        } else if (Names.permission(project.permissionPrefix(), suffix).length() > Names.PERMISSION_MAX_LENGTH) {
            errors.add(new FieldError("suffix", "Yetki adı en fazla " + Names.PERMISSION_MAX_LENGTH + " karakter olabilir"));
        }
        List<String> targets = body.environments() == null || body.environments().isEmpty()
                ? environments.all().stream().map(Environment::code).toList() : body.environments();
        for (int i = 0; i < targets.size(); i++) {
            if (environments.find(targets.get(i)).isEmpty()) {
                errors.add(new FieldError("environments[" + i + "]", "Ortam yok"));
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation("Yetki", errors);
        }
        String code = Names.permission(project.permissionPrefix(), suffix);
        List<PermissionResult> results = new ArrayList<>();
        for (String environment : targets) {
            results.add(new PermissionResult(environment, authorization.create(environment, code, body.description())));
        }
        if (results.stream().noneMatch(PermissionResult::created)) {
            throw ApiException.conflict("DUPLICATE", "Yetki seçilen ortamlarda zaten var: " + code);
        }
        return new CreatedPermission(code, results);
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
                        .map(env -> new EnvironmentView(env.environment(), env.baseUrl(), env.specPath(), env.enabled()))
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
