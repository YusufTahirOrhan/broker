package com.acme.yapi.admin.route;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.acme.yapi.admin.authz.AuthorizationService;
import com.acme.yapi.admin.config.AdminProperties;
import com.acme.yapi.admin.db.Versions;
import com.acme.yapi.admin.environment.Environment;
import com.acme.yapi.admin.environment.EnvironmentRepository;
import com.acme.yapi.admin.profile.ProfileRepository;
import com.acme.yapi.admin.project.ProjectRepository;
import com.acme.yapi.admin.web.ApiException;
import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.naming.Names;
import com.acme.yapi.core.request.BackendRequest;
import com.acme.yapi.core.request.BackendRequestBuilder;
import com.acme.yapi.core.request.RequestBodyValidator;
import com.acme.yapi.core.request.RequestBuildException;
import com.acme.yapi.core.request.RequestContext;
import com.acme.yapi.core.route.HttpMethodName;
import com.acme.yapi.core.route.PathTemplate;
import com.acme.yapi.core.route.RouteDefinition;
import com.acme.yapi.core.route.RouteDefinitionParser;
import com.acme.yapi.core.route.RouteValidator;
import com.acme.yapi.core.spec.OpenApiSpec;
import com.acme.yapi.core.testcall.TestCallResult;
import com.acme.yapi.core.validation.FieldError;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class RouteService {

    private final RouteRepository routes;
    private final ProjectRepository projects;
    private final ProfileRepository profiles;
    private final AuthorizationService authorization;
    private final EnvironmentRepository environments;
    private final RouteValidator.Context readable;

    public RouteService(RouteRepository routes, ProjectRepository projects, ProfileRepository profiles,
                        AuthorizationService authorization, EnvironmentRepository environments,
                        AdminProperties properties) {
        this.routes = routes;
        this.projects = projects;
        this.profiles = profiles;
        this.authorization = authorization;
        this.environments = environments;
        this.readable = RouteValidator.Context.of(properties.readableHeaders());
    }

    /** A subtask as the editor sends it; {@code version} is the version that was read (updates only). */
    public record Body(Long projectId, String subtask, String httpMethod, String pathTemplate, List<String> permissions,
                       List<String> foreignPermissions, Boolean anonymous, Long responseProfileId, JsonNode definition,
                       JsonNode specOperation, Integer version, String comment) {
    }

    public record ProfileRef(long id, String name) {
    }

    public record DeploymentView(String environment, int version, String status, String activatedBy,
                                 Instant activatedAt, String lastProbeStatus, Instant lastProbeAt) {
    }

    public record Summary(long id, long projectId, String maintask, String subtask, String httpMethod,
                          String pathTemplate, List<String> permissions, List<String> foreignPermissions,
                          boolean anonymous, ProfileRef responseProfile, String status, int version, String updatedBy,
                          Instant updatedAt, List<DeploymentView> deployments) {
    }

    public record View(long id, long projectId, String maintask, String subtask, String httpMethod,
                       String pathTemplate, List<String> permissions, List<String> foreignPermissions,
                       boolean anonymous, Long responseProfileId, ProfileRef responseProfile, JsonNode definition,
                       JsonNode specOperation, String specFingerprint, String status, int version, String createdBy,
                       Instant createdAt, String updatedBy, Instant updatedAt, List<DeploymentView> deployments) {
    }

    public List<Summary> list(long projectId, String status, String query) {
        projects.find(projectId).orElseThrow(() -> ApiException.notFound("Proje yok: " + projectId));
        Map<Long, List<DeploymentView>> deployments = new HashMap<>();
        for (RouteRepository.Deployment deployment : routes.deploymentsOfProject(projectId)) {
            deployments.computeIfAbsent(deployment.definitionId(), id -> new ArrayList<>()).add(view(deployment));
        }
        return routes.list(projectId, status, query).stream()
                .map(row -> new Summary(row.id(), row.projectId(), row.maintask(), row.subtask(), row.httpMethod(),
                        row.pathTemplate(), row.permissions(), row.foreignPermissions(), row.anonymous(),
                        profileRef(row), row.status(), row.version(), row.updatedBy(), row.updatedAt(),
                        deployments.getOrDefault(row.id(), List.of())))
                .toList();
    }

    public View get(long id) {
        RouteRepository.Row row = require(id);
        return new View(row.id(), row.projectId(), row.maintask(), row.subtask(), row.httpMethod(), row.pathTemplate(),
                row.permissions(), row.foreignPermissions(), row.anonymous(), row.responseProfileId(), profileRef(row),
                row.definition(), row.specOperation(), row.specFingerprint(), row.status(), row.version(),
                row.createdBy(), row.createdAt(), row.updatedBy(), row.updatedAt(),
                routes.deployments(id).stream().map(RouteService::view).toList());
    }

    public record Validation(boolean valid, List<FieldError> errors) {
    }

    /** The form's live check: the same rules as saving, without touching the authorization service. */
    public Validation validate(Body body) {
        ProjectRepository.Row project = body.projectId() == null ? null : projects.find(body.projectId()).orElse(null);
        List<FieldError> errors = new ArrayList<>();
        if (body.projectId() != null && project == null) {
            errors.add(new FieldError("projectId", "Proje yok"));
        }
        errors.addAll(rules(project, body, body.subtask() != null));
        return new Validation(errors.isEmpty(), errors);
    }

    @Transactional
    public View create(String user, long projectId, Body body) {
        ProjectRepository.Row project = projects.find(projectId)
                .orElseThrow(() -> ApiException.notFound("Proje yok: " + projectId));
        List<FieldError> errors = rules(project, body, true);
        if (errors.isEmpty()) {
            errors.addAll(missingPermissions(body));
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation("Tanım", errors);
        }
        try {
            return get(routes.insert(projectId, body.subtask(), content(body), user, body.comment()));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("DUPLICATE",
                    "Bu projede aynı adlı bir subtask var (büyük/küçük harf fark etmez): " + body.subtask());
        }
    }

    @Transactional
    public View update(String user, long id, Body body) {
        RouteRepository.Row current = require(id);
        if ("ARCHIVED".equals(current.status())) {
            throw ApiException.conflict("INVALID_STATE", "Arşivdeki subtask değiştirilemez; önce geri açın.");
        }
        ProjectRepository.Row project = projects.find(current.projectId()).orElseThrow();
        List<FieldError> errors = rules(project, body, false);
        if (body.subtask() != null && !body.subtask().equals(current.subtask())) {
            errors.add(new FieldError("subtask", "Subtask adı değiştirilemez; yeni adla yeni bir subtask açılır"));
        }
        if (body.version() == null) {
            errors.add(new FieldError("version", "Okunan versiyon zorunlu"));
        }
        if (errors.isEmpty()) {
            errors.addAll(missingPermissions(body));
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation("Tanım", errors);
        }
        Optional<Integer> written = routes.update(id, body.version(), content(body), user, "UPDATE", body.comment());
        if (written.isEmpty()) {
            throw versionConflict(id);
        }
        return get(id);
    }

    public record StatusChange(Integer version, String comment) {
    }

    /** Only a subtask that is ACTIVE in no environment can be archived. */
    @Transactional
    public View archive(String user, long id, StatusChange body) {
        RouteRepository.Row current = require(id);
        if (!"ACTIVE".equals(current.status())) {
            throw ApiException.conflict("INVALID_STATE", "Subtask zaten arşivde.");
        }
        List<String> active = routes.deployments(id).stream().filter(d -> "ACTIVE".equals(d.status()))
                .map(RouteRepository.Deployment::environment).toList();
        if (!active.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "ROUTE_DEPLOYED",
                    "Subtask şu ortamlarda aktif: " + String.join(", ", active) + ". Önce kapatılmalı.", List.of(),
                    Map.of("environments", active));
        }
        return changeStatus(user, current, body, "ARCHIVED", "ARCHIVE");
    }

    @Transactional
    public View restore(String user, long id, StatusChange body) {
        RouteRepository.Row current = require(id);
        if (!"ARCHIVED".equals(current.status())) {
            throw ApiException.conflict("INVALID_STATE", "Subtask arşivde değil.");
        }
        return changeStatus(user, current, body, "ACTIVE", "RESTORE");
    }

    private View changeStatus(String user, RouteRepository.Row current, StatusChange body, String status,
                              String action) {
        int expected = body == null || body.version() == null ? current.version() : body.version();
        if (routes.status(current.id(), expected, status, user, action, body == null ? null : body.comment()).isEmpty()) {
            throw versionConflict(current.id());
        }
        return get(current.id());
    }

    public record Rollback(Integer restoreVersion, Integer version, String comment) {
    }

    /** Writes an old version as the new current one; environments are not touched. */
    @Transactional
    public View rollback(String user, long id, Rollback body) {
        RouteRepository.Row current = require(id);
        if (body.restoreVersion() == null) {
            throw ApiException.validation(List.of(new FieldError("restoreVersion", "Dönülecek versiyon zorunlu")));
        }
        JsonNode snapshot = routes.history(id, body.restoreVersion())
                .orElseThrow(() -> ApiException.notFound("Versiyon yok: v" + body.restoreVersion())).snapshot();
        JsonNode profile = snapshot.get("response_profile_id");
        RouteRepository.Content content = new RouteRepository.Content(Json.text(snapshot.get("http_method")),
                Json.text(snapshot.get("path_template")), RouteSnapshots.strings(snapshot.get("permissions")),
                RouteSnapshots.strings(snapshot.get("foreign_permissions")), snapshot.get("anonymous").booleanValue(),
                profile == null || profile.isNull() ? null : profile.longValue(), snapshot.get("definition"),
                Json.isAbsent(snapshot.get("spec_operation")) ? null : snapshot.get("spec_operation"),
                Json.text(snapshot.get("spec_fingerprint")));
        int expected = body.version() == null ? current.version() : body.version();
        String comment = body.comment() == null ? "v" + body.restoreVersion() + " versiyonuna dönüldü" : body.comment();
        if (routes.update(id, expected, content, user, "ROLLBACK", comment).isEmpty()) {
            throw versionConflict(id);
        }
        return get(id);
    }

    public List<Versions.Entry> history(long id) {
        require(id);
        List<RouteRepository.Deployment> deployments = routes.deployments(id);
        return routes.history(id).stream()
                .map(row -> new Versions.Entry(row.version(), row.action(), row.changedBy(), row.changedAt(),
                        row.comment(), deployments.stream()
                        .filter(d -> d.version() == row.version() && "ACTIVE".equals(d.status()))
                        .map(RouteRepository.Deployment::environment).toList()))
                .toList();
    }

    public record VersionView(int version, String action, String changedBy, Instant changedAt, String comment,
                              JsonNode snapshot) {
    }

    public VersionView version(long id, int version) {
        RouteRepository.HistoryRow row = routes.history(id, version)
                .orElseThrow(() -> ApiException.notFound("Versiyon yok: v" + version));
        return new VersionView(row.version(), row.action(), row.changedBy(), row.changedAt(), row.comment(),
                RouteSnapshots.view(row.snapshot()));
    }

    public record Comparison(int from, int to, List<Versions.Change> changes, List<String> foreignPermissionsAdded,
                             List<String> foreignPermissionsRemoved, boolean opensAnonymous) {
    }

    public Comparison compare(long id, int from, int to) {
        JsonNode before = version(id, from).snapshot();
        JsonNode after = version(id, to).snapshot();
        return new Comparison(from, to, Versions.changes(before, after), RouteSnapshots.foreignAdded(before, after),
                RouteSnapshots.foreignAdded(after, before), RouteSnapshots.opensAnonymous(before, after));
    }

    public record PermissionRoute(long routeId, long projectId, String maintask, String subtask, String usage,
                                  String status) {
    }

    public List<PermissionRoute> usingPermission(String code) {
        return routes.usingPermission(code).stream()
                .map(use -> new PermissionRoute(use.routeId(), use.projectId(), use.maintask(), use.subtask(),
                        use.usage(), use.status()))
                .toList();
    }

    /**
     * {@code /routes/preview} and {@code /projects/{id}/test}: an unsaved definition and a sample body.
     * {@code permissions} and {@code foreignPermissions} are the form's lists; the trial user is taken to hold them, so
     * {@code ${user.permission}} is the first of them.
     */
    public record Trial(Long projectId, String environment, String httpMethod, String pathTemplate,
                        Long responseProfileId, JsonNode definition, JsonNode sample, Map<String, String> headers,
                        String userId, List<String> permissions, List<String> foreignPermissions) {

        /** What {@code ${user.permission}} stands for in a preview or test call. */
        public String permission() {
            List<String> all = new ArrayList<>(permissions == null ? List.of() : permissions);
            all.addAll(foreignPermissions == null ? List.of() : foreignPermissions);
            return RequestContext.firstPermission(all, all);
        }
    }

    public record Preview(TestCallResult.RequestView request, List<FieldError> errors) {
    }

    /** The request the broker would send, built with the broker's own builder, without calling anything. */
    public Preview preview(String user, Trial trial) {
        ProjectRepository.Row project = trial.projectId() == null ? null
                : projects.find(trial.projectId()).orElseThrow(() -> ApiException.notFound("Proje yok: " + trial.projectId()));
        if (project == null) {
            throw ApiException.validation(List.of(new FieldError("projectId", "Zorunlu")));
        }
        checkTrialDefinition(trial);
        Environment environment = trial.environment() == null ? environments.first()
                : environments.require(trial.environment());
        String baseUrl = projects.environment(project.id(), environment.code())
                .map(ProjectRepository.EnvironmentRow::baseUrl)
                .orElseThrow(() -> ApiException.validation(List.of(new FieldError("environment",
                        "Proje " + environment.code() + " ortamında tanımlı değil"))));
        if (trial.sample() == null || !trial.sample().isObject()) {
            return new Preview(null, List.of(new FieldError("sample", "Örnek gövde bir JSON nesnesi olmalı")));
        }
        RouteDefinition definition = RouteDefinitionParser.parse(trial.definition()).definition();
        RequestBodyValidator.Result validated = RequestBodyValidator.validate((ObjectNode) trial.sample(),
                definition.request().fields());
        if (!validated.valid()) {
            return new Preview(null, validated.errors());
        }
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (trial.headers() != null) {
            headers.putAll(trial.headers());
        }
        try {
            BackendRequest request = BackendRequestBuilder.build(HttpMethodName.parse(trial.httpMethod()).orElseThrow(),
                    PathTemplate.parse(trial.pathTemplate()), definition, baseUrl,
                    new RequestContext((ObjectNode) trial.sample(), validated.effectiveBody(), headers::get, readable,
                            trial.userId() == null ? user : trial.userId(), trial.permission(),
                            "00000000-0000-7000-8000-000000000000"));
            return new Preview(TestCallResult.RequestView.of(request), List.of());
        } catch (RequestBuildException e) {
            return new Preview(null, List.of(e.error()));
        }
    }

    /** A test or preview with a broken definition is refused like saving it would be. */
    public void checkTrialDefinition(Trial trial) {
        List<FieldError> errors = RouteValidator.validate(trial.httpMethod(), trial.pathTemplate(), trial.definition(),
                readable);
        if (!errors.isEmpty()) {
            throw ApiException.validation("Tanım", errors);
        }
    }

    public RouteRepository.Row require(long id) {
        return routes.find(id).orElseThrow(() -> ApiException.notFound("Subtask yok: " + id));
    }

    private List<FieldError> rules(ProjectRepository.Row project, Body body, boolean isNew) {
        List<FieldError> errors = new ArrayList<>();
        if (isNew && !Names.isValidSubtask(body.subtask())) {
            errors.add(new FieldError("subtask", "snake_case olmalı, örneğin get_order"));
        }
        errors.addAll(RouteValidator.validate(body.httpMethod(), body.pathTemplate(), body.definition(), readable));
        List<String> own = body.permissions() == null ? List.of() : body.permissions();
        List<String> foreign = body.foreignPermissions() == null ? List.of() : body.foreignPermissions();
        if (project != null) {
            List<String> otherPrefixes = projects.prefixes().stream()
                    .filter(prefix -> !prefix.equals(project.permissionPrefix())).toList();
            for (int i = 0; i < own.size(); i++) {
                if (!Names.belongsTo(own.get(i), project.permissionPrefix())) {
                    errors.add(new FieldError("permissions[" + i + "]",
                            "Projenin önekiyle başlamalı: " + project.permissionPrefix() + "_"));
                }
            }
            for (int i = 0; i < foreign.size(); i++) {
                if (Names.belongsTo(foreign.get(i), project.permissionPrefix())
                        || Names.ownerPrefix(foreign.get(i), otherPrefixes).isEmpty()) {
                    errors.add(new FieldError("foreignPermissions[" + i + "]",
                            "Kayıtlı başka bir projenin önekiyle başlamalı"));
                }
            }
        }
        if (!Boolean.TRUE.equals(body.anonymous()) && own.isEmpty() && foreign.isEmpty()) {
            errors.add(new FieldError("permissions", "Anonim olmayan subtask en az bir yetki ister"));
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String permission : concat(own, foreign)) {
            if (!seen.add(permission)) {
                errors.add(new FieldError("permissions", "Yetki iki kez seçilmiş: " + permission));
            }
        }
        if (body.responseProfileId() != null && profiles.find(body.responseProfileId()).isEmpty()) {
            errors.add(new FieldError("responseProfileId", "Profil yok"));
        }
        if (body.specOperation() != null && !body.specOperation().isNull() && !body.specOperation().isObject()) {
            errors.add(new FieldError("specOperation", "Spec operasyonu bir JSON nesnesi olmalı"));
        }
        return errors;
    }

    /** Every permission must exist in the first environment's authorization service when the subtask is saved. */
    private List<FieldError> missingPermissions(Body body) {
        String environment = environments.first().code();
        List<String> own = body.permissions() == null ? List.of() : body.permissions();
        List<String> foreign = body.foreignPermissions() == null ? List.of() : body.foreignPermissions();
        Set<String> existing = authorization.existing(environment, concat(own, foreign));
        List<FieldError> errors = new ArrayList<>();
        for (int i = 0; i < own.size(); i++) {
            if (!existing.contains(own.get(i))) {
                errors.add(new FieldError("permissions[" + i + "]", environment + " yetkilendirme servisinde yok: " + own.get(i)));
            }
        }
        for (int i = 0; i < foreign.size(); i++) {
            if (!existing.contains(foreign.get(i))) {
                errors.add(new FieldError("foreignPermissions[" + i + "]",
                        environment + " yetkilendirme servisinde yok: " + foreign.get(i)));
            }
        }
        return errors;
    }

    private static RouteRepository.Content content(Body body) {
        JsonNode operation = body.specOperation() == null || body.specOperation().isNull() ? null : body.specOperation();
        return new RouteRepository.Content(body.httpMethod(), body.pathTemplate(),
                body.permissions() == null ? List.of() : body.permissions(),
                body.foreignPermissions() == null ? List.of() : body.foreignPermissions(),
                Boolean.TRUE.equals(body.anonymous()), body.responseProfileId(), body.definition(), operation,
                operation == null ? null : OpenApiSpec.fingerprint(operation));
    }

    private ApiException versionConflict(long id) {
        return new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "Subtask arada başkası tarafından değiştirildi.",
                List.of(), Map.of("currentVersion", require(id).version()));
    }

    private static ProfileRef profileRef(RouteRepository.Row row) {
        return row.responseProfileId() == null ? null : new ProfileRef(row.responseProfileId(), row.profileName());
    }

    private static DeploymentView view(RouteRepository.Deployment deployment) {
        return new DeploymentView(deployment.environment(), deployment.version(), deployment.status(),
                deployment.activatedBy(), deployment.activatedAt(), deployment.lastProbeStatus(),
                deployment.lastProbeAt());
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }
}
