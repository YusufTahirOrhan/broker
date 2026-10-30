package com.acme.yapi.admin.promotion;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.acme.yapi.admin.authz.AuthorizationService;
import com.acme.yapi.admin.db.Versions;
import com.acme.yapi.admin.environment.Environment;
import com.acme.yapi.admin.environment.EnvironmentRepository;
import com.acme.yapi.admin.plugin.PluginRepository;
import com.acme.yapi.admin.plugin.PluginService;
import com.acme.yapi.admin.profile.ProfileRepository;
import com.acme.yapi.admin.profile.ProfileService;
import com.acme.yapi.admin.project.ProjectRepository;
import com.acme.yapi.admin.route.RouteRepository;
import com.acme.yapi.admin.route.RouteSnapshots;
import com.acme.yapi.admin.security.Role;
import com.acme.yapi.admin.security.User;
import com.acme.yapi.admin.web.ApiException;
import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.route.AlarmRoute;
import com.acme.yapi.core.route.RouteDefinitionParser;
import com.acme.yapi.core.validation.FieldError;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.JsonNode;

/**
 * Promotion requests ("Promote akışı"): pre-checks, approval with four eyes, and applying through the target
 * environment's Admin API.
 */
@Service
public class PromotionService {

    static final Set<String> ROUTE_ACTIONS = Set.of("DEPLOY", "DISABLE", "ENABLE", "ROLLBACK");
    static final Set<String> PROFILE_ACTIONS = Set.of("DEPLOY", "ROLLBACK");
    static final List<String> OPEN = List.of("PENDING_APPROVAL", "APPROVED", "APPLYING");

    private final PromotionRepository promotions;
    private final PromotionApplier applier;
    private final RouteRepository routes;
    private final ProfileRepository profiles;
    private final PluginRepository plugins;
    private final ProjectRepository projects;
    private final EnvironmentRepository environments;
    private final AuthorizationService authorization;

    public PromotionService(PromotionRepository promotions, PromotionApplier applier, RouteRepository routes,
                            ProfileRepository profiles, PluginRepository plugins, ProjectRepository projects,
                            EnvironmentRepository environments, AuthorizationService authorization) {
        this.promotions = promotions;
        this.applier = applier;
        this.routes = routes;
        this.profiles = profiles;
        this.plugins = plugins;
        this.projects = projects;
        this.environments = environments;
        this.authorization = authorization;
    }

    public record Create(String subjectType, Long subjectId, Integer version, String targetEnv, String action,
                         String reason) {
    }

    public record Check(String check, boolean ok, String message) {
    }

    public record Diff(Integer deployedVersion, List<Versions.Change> changes, List<String> foreignPermissionsAdded,
                       boolean opensAnonymous) {
    }

    public record AffectedRoute(long routeId, String maintask, String subtask, int version) {
    }

    public record View(long id, String subjectType, long subjectId, String subjectName, int version, String sourceEnv,
                       String targetEnv, String action, String status, boolean requiresApproval, String requestedBy,
                       Instant requestedAt, String reason, String decidedBy, Instant decidedAt, String decisionNote,
                       String probeStatus, Long configVersion, Instant appliedAt, String failureReason,
                       @JsonInclude(JsonInclude.Include.NON_NULL) Diff diff,
                       @JsonInclude(JsonInclude.Include.NON_NULL) List<AffectedRoute> affectedRoutes,
                       List<String> allowedActions) {
    }

    public record Page(List<View> items, int page, int size, long totalItems) {
    }

    /** What a request moves: the version's snapshot in the screens' camelCase form. */
    private record Subject(SubjectType type, long id, String name, Long projectId, String profileName,
                           JsonNode snapshot) {

        boolean route() {
            return type == SubjectType.ROUTE;
        }
    }

    public View create(User user, Create body) {
        user.require(Role.ROUTE_EDITOR);
        List<FieldError> errors = new ArrayList<>();
        Optional<SubjectType> type = SubjectType.parse(body.subjectType());
        boolean route = type.orElse(null) == SubjectType.ROUTE;
        if (type.isEmpty()) {
            errors.add(new FieldError("subjectType", "ROUTE, PROFILE ya da PLUGIN olmalı"));
        }
        if (body.subjectId() == null) {
            errors.add(new FieldError("subjectId", "Zorunlu"));
        }
        if (body.action() == null || !(route ? ROUTE_ACTIONS : PROFILE_ACTIONS).contains(body.action())) {
            errors.add(new FieldError("action", route ? "DEPLOY, DISABLE, ENABLE ya da ROLLBACK olmalı"
                    : "Profil ve eklentide yalnızca DEPLOY ve ROLLBACK"));
        }
        if (body.targetEnv() == null || environments.find(body.targetEnv()).isEmpty()) {
            errors.add(new FieldError("targetEnv", "Ortam yok"));
        }
        boolean followsTarget = "DISABLE".equals(body.action()) || "ENABLE".equals(body.action());
        if (body.version() == null && !followsTarget) {
            errors.add(new FieldError("version", "Zorunlu"));
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation("Talep", errors);
        }
        Environment target = environments.require(body.targetEnv());
        int version = body.version() == null ? 0 : body.version();
        if (followsTarget) {
            // DISABLE and ENABLE act on whatever version the target serves; without one the pre-check reports it
            Optional<RouteRepository.Deployment> deployed = routes.deployment(body.subjectId(), target.code());
            if (deployed.isPresent()) {
                version = deployed.get().version();
            } else if (body.version() == null) {
                version = routes.find(body.subjectId()).map(RouteRepository.Row::version)
                        .orElseThrow(() -> ApiException.notFound("Subtask yok: " + body.subjectId()));
            }
        }
        Subject subject = subject(type.get(), body.subjectId(), version);
        if (subject.type() == SubjectType.PLUGIN && PluginService.isGlobal(subject.snapshot().get("definition"))) {
            // a GLOBAL plugin reaches every subtask: only an APPROVER moves it
            user.require(Role.APPROVER);
        }
        Optional<Environment> source = "DEPLOY".equals(body.action()) ? environments.previous(target) : Optional.empty();
        List<Check> checks = prechecks(subject, version, target, source, body.action());
        long failed = checks.stream().filter(check -> !check.ok()).count();
        if (failed > 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "PRECHECK_FAILED",
                    "Talep açılamadı: " + failed + " kontrol geçmedi.", List.of(), Map.of("checks", checks));
        }
        boolean approval = target.requiresApproval() || (route && opensAnonymous(subject, target));
        if (approval && (body.reason() == null || body.reason().isBlank())) {
            throw ApiException.validation("Talep", List.of(new FieldError("reason",
                    "Onay isteyen taleplerde gerekçe ya da değişiklik kaydı numarası zorunlu")));
        }
        long id;
        try {
            id = promotions.insert(subject.type(), subject.id(), version, source.map(Environment::code).orElse(null),
                    target.code(), body.action(), approval ? "PENDING_APPROVAL" : "APPLYING", user.username(),
                    body.reason());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("INVALID_STATE", "Aynı hedef ortam için açık bir talep var.");
        }
        if (!approval) {
            applier.apply(id, user.username());
        }
        return get(user, id);
    }

    public View get(User user, long id) {
        return view(user, require(id), true);
    }

    public Page list(User user, String status, String targetEnv, String subjectType, Long projectId, boolean mine,
                     int page, int size) {
        int pageSize = Math.min(Math.max(size, 1), 100);
        int pageNumber = Math.max(page, 0);
        List<String> statuses = status == null || status.isBlank() ? List.of()
                : Arrays.stream(status.split(",")).map(String::strip).filter(value -> !value.isEmpty()).toList();
        PromotionRepository.Filter filter = new PromotionRepository.Filter(statuses,
                targetEnv == null || targetEnv.isBlank() ? null : targetEnv,
                subjectType == null || subjectType.isBlank() ? null : subjectType, projectId,
                mine ? user.username() : null);
        List<View> items = promotions.page(filter, pageNumber, pageSize).stream()
                .map(row -> view(user, row, false)).toList();
        return new Page(items, pageNumber, pageSize, promotions.count(filter));
    }

    public record Decision(String note) {
    }

    public View approve(User user, long id, Decision body) {
        user.require(Role.APPROVER);
        PromotionRepository.Row request = require(id);
        fourEyes(user, request);
        if (!promotions.decide(id, "APPROVED", user.username(), body == null ? null : body.note())) {
            throw invalidState(request);
        }
        promotions.transition(id, List.of("APPROVED"), "APPLYING");
        applier.apply(id, user.username());
        return get(user, id);
    }

    public View reject(User user, long id, Decision body) {
        user.require(Role.APPROVER);
        PromotionRepository.Row request = require(id);
        fourEyes(user, request);
        if (body == null || body.note() == null || body.note().isBlank()) {
            throw ApiException.validation("Ret", List.of(new FieldError("note", "Ret notu zorunlu")));
        }
        if (!promotions.decide(id, "REJECTED", user.username(), body.note())) {
            throw invalidState(request);
        }
        return get(user, id);
    }

    public View cancel(User user, long id) {
        user.require(Role.ROUTE_EDITOR);
        PromotionRepository.Row request = require(id);
        if (!request.requestedBy().equals(user.username())) {
            throw ApiException.forbidden("Talebi yalnızca açan kişi iptal edebilir.");
        }
        if (!promotions.transition(id, List.of("PENDING_APPROVAL"), "CANCELLED")) {
            throw invalidState(request);
        }
        return get(user, id);
    }

    /** A FAILED request, or one left APPLYING by a lost answer, is applied again with the same promotion ID. */
    public View retry(User user, long id) {
        user.require(Role.ROUTE_EDITOR);
        PromotionRepository.Row request = require(id);
        if ("FAILED".equals(request.status())) {
            try {
                if (!promotions.transition(id, List.of("FAILED"), "APPLYING")) {
                    throw invalidState(request);
                }
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("INVALID_STATE", "Aynı hedef ortam için açık başka bir talep var.");
            }
        } else if (!"APPLYING".equals(request.status())) {
            throw invalidState(request);
        }
        applier.apply(id, user.username());
        return get(user, id);
    }

    private List<Check> prechecks(Subject subject, int version, Environment target, Optional<Environment> source,
                                  String action) {
        List<Check> checks = new ArrayList<>();
        boolean deploy = "DEPLOY".equals(action);
        boolean rollback = "ROLLBACK".equals(action);
        boolean switches = "DISABLE".equals(action) || "ENABLE".equals(action);
        if (subject.route() && !"DISABLE".equals(action)) {
            boolean archived = "ARCHIVED".equals(routes.find(subject.id()).orElseThrow().status());
            checks.add(new Check("SUBJECT_ACTIVE", !archived, archived ? "Subtask arşivde" : "Subtask arşivde değil"));
        }
        if (deploy) {
            checks.add(new Check("NEXT_ENVIRONMENT", true, source.isEmpty()
                    ? target.code() + " ilk ortam" : target.code() + ", " + source.get().code() + " ortamının hemen sonrası"));
            if (source.isPresent()) {
                boolean active = activeIn(subject, version, source.get().code());
                checks.add(new Check("SOURCE_ACTIVE", active, "v" + version + " " + source.get().code() + " ortamında "
                        + (active ? "aktif" : "aktif değil; yalnızca bir önceki ortamda aktif versiyon taşınır")));
            }
        }
        Optional<Long> open = promotions.open(subject.type(), subject.id(), target.code());
        checks.add(new Check("NO_OPEN_REQUEST", open.isEmpty(), open.isEmpty() ? "Açık talep yok"
                : "Aynı hedef için açık talep var: #" + open.get()));
        if (rollback) {
            boolean applied = promotions.previouslyApplied(subject.type(), subject.id(), version, target.code());
            checks.add(new Check("PREVIOUSLY_APPLIED", applied, "v" + version + " " + target.code()
                    + (applied ? " ortamına daha önce uygulanmış" : " ortamına daha önce uygulanmamış")));
        }
        if (switches) {
            Optional<RouteRepository.Deployment> deployed = routes.deployment(subject.id(), target.code());
            String wanted = "DISABLE".equals(action) ? "ACTIVE" : "DISABLED";
            boolean ok = deployed.isPresent() && wanted.equals(deployed.get().status());
            checks.add(new Check("DEPLOYED_IN_TARGET", ok, deployed.isEmpty() ? "Subtask " + target.code() + " ortamında yok"
                    : "v" + deployed.get().version() + " " + target.code() + " ortamında " + deployed.get().status()));
        }
        if (subject.route() && !"DISABLE".equals(action)) {
            checks.add(permissionsCheck(subject, target));
            checks.add(profileCheck(subject, target));
            checks.add(projectCheck(subject, target));
            alarmCheck(subject, target).ifPresent(checks::add);
            pluginsCheck(subject, target).ifPresent(checks::add);
        }
        return checks;
    }

    /** The SUBTASK plugins the subtask chooses must be active in the target, or its requests would go without them. */
    private Optional<Check> pluginsCheck(Subject subject, Environment target) {
        List<String> chosen = RouteSnapshots.strings(subject.snapshot().path("definition").get("plugins"));
        if (chosen.isEmpty()) {
            return Optional.empty();
        }
        List<String> missing = new ArrayList<>();
        for (String name : chosen) {
            boolean active = plugins.list().stream().filter(row -> com.acme.yapi.core.naming.Names.sameName(row.name(), name))
                    .findFirst().flatMap(row -> plugins.deployment(row.id(), target.code())).isPresent();
            if (!active) {
                missing.add(name);
            }
        }
        return Optional.of(new Check("PLUGINS_ACTIVE", missing.isEmpty(), missing.isEmpty()
                ? "Seçilen eklentiler " + target.code() + " ortamında aktif"
                : target.code() + " ortamında aktif olmayan eklenti: " + String.join(", ", missing)
                        + "; önce eklenti taşınmalı"));
    }

    private Check permissionsCheck(Subject subject, Environment target) {
        List<String> codes = new ArrayList<>(RouteSnapshots.strings(subject.snapshot().get("permissions")));
        codes.addAll(RouteSnapshots.strings(subject.snapshot().get("foreignPermissions")));
        if (codes.isEmpty()) {
            return new Check("PERMISSIONS_EXIST", true, "Anonim subtask; yetki yok");
        }
        try {
            Set<String> existing = authorization.existing(target.code(), codes);
            List<String> missing = codes.stream().filter(code -> !existing.contains(code)).toList();
            return new Check("PERMISSIONS_EXIST", missing.isEmpty(), missing.isEmpty()
                    ? "Yetkiler " + target.code() + " Koordinatör'ünde tanımlı"
                    : target.code() + " Koordinatör'ünde yok: " + String.join(", ", missing));
        } catch (ApiException e) {
            return new Check("PERMISSIONS_EXIST", false, e.getMessage());
        }
    }

    private Check profileCheck(Subject subject, Environment target) {
        JsonNode profileId = subject.snapshot().get("responseProfileId");
        if (profileId == null || profileId.isNull()) {
            return new Check("PROFILE_ACTIVE", true, "Yanıt profili yok");
        }
        ProfileRepository.Row profile = profiles.find(profileId.longValue()).orElseThrow();
        Optional<ProfileRepository.Deployment> deployed = profiles.deployment(profile.id(), target.code());
        return new Check("PROFILE_ACTIVE", deployed.isPresent(), deployed.isPresent()
                ? profile.name() + " v" + deployed.get().version() + " " + target.code() + " ortamında aktif"
                : profile.name() + " " + target.code() + " ortamında aktif değil; önce profil taşınmalı");
    }

    private Check projectCheck(Subject subject, Environment target) {
        Optional<ProjectRepository.EnvironmentRow> settings = projects.environment(subject.projectId(), target.code());
        boolean ok = settings.isPresent() && settings.get().enabled();
        return new Check("PROJECT_ENABLED", ok, settings.isEmpty() ? "Proje " + target.code() + " ortamında tanımlı değil"
                : ok ? "Proje " + target.code() + " ortamında açık" : "Proje " + target.code() + " ortamında kapalı");
    }

    /**
     * The alarm service route a subtask's requests go through must be active in the target, or every request to it
     * would fail its alarm check. None for alarm service routes themselves and for subtasks with the check off.
     */
    private Optional<Check> alarmCheck(Subject subject, Environment target) {
        JsonNode definition = subject.snapshot().path("definition");
        if (RouteDefinitionParser.isAlarmService(definition)) {
            return Optional.empty();
        }
        String check = Json.text(definition.path("alarm").get("check"));
        if ("OFF".equals(check)) {
            return Optional.empty();
        }
        AlarmRoute alarm;
        if ("ROUTE".equals(check)) {
            alarm = new AlarmRoute(Json.text(definition.path("alarm").get("maintask")),
                    Json.text(definition.path("alarm").get("subtask")));
        } else {
            alarm = projects.environment(subject.projectId(), target.code())
                    .map(ProjectRepository.EnvironmentRow::alarmRoute).orElse(null);
            if (alarm == null) {
                return Optional.of(new Check("ALARM_ROUTE_ACTIVE", true,
                        "Projenin " + target.code() + " ortamında alarm route'u yok; alarm kontrolü yapılmaz"));
            }
        }
        String name = alarm.maintask() + "/" + alarm.subtask();
        Optional<RouteRepository.Row> route = routes.find(alarm.maintask(), alarm.subtask());
        if (route.isEmpty()) {
            return Optional.of(new Check("ALARM_ROUTE_ACTIVE", false, "Alarm route'u yok: " + name));
        }
        Optional<RouteRepository.Deployment> deployed = routes.deployment(route.get().id(), target.code())
                .filter(deployment -> "ACTIVE".equals(deployment.status()));
        boolean service = deployed.flatMap(deployment -> routes.history(route.get().id(), deployment.version()))
                .map(history -> RouteDefinitionParser.isAlarmService(history.snapshot().get("definition")))
                .orElse(false);
        return Optional.of(new Check("ALARM_ROUTE_ACTIVE", deployed.isPresent() && service, deployed.isEmpty()
                ? "Alarm route'u " + name + " " + target.code() + " ortamında aktif değil; önce o taşınmalı"
                : service ? "Alarm route'u " + name + " v" + deployed.get().version() + " " + target.code()
                        + " ortamında aktif"
                        : "Alarm route'u " + name + " " + target.code() + " ortamındaki versiyonunda alarm servisi değil"));
    }

    private boolean activeIn(Subject subject, int version, String environment) {
        return switch (subject.type()) {
            case ROUTE -> routes.deployment(subject.id(), environment)
                    .filter(deployment -> deployment.version() == version && "ACTIVE".equals(deployment.status()))
                    .isPresent();
            case PROFILE -> profiles.deployment(subject.id(), environment)
                    .filter(deployment -> deployment.version() == version).isPresent();
            case PLUGIN -> plugins.deployment(subject.id(), environment)
                    .filter(deployment -> deployment.version() == version).isPresent();
        };
    }

    /** Anonymous access needs approval in every environment: the version is anonymous and what runs there is not. */
    private boolean opensAnonymous(Subject subject, Environment target) {
        return RouteSnapshots.opensAnonymous(deployedSnapshot(subject, target.code()), subject.snapshot());
    }

    private JsonNode deployedSnapshot(Subject subject, String environment) {
        return switch (subject.type()) {
            case ROUTE -> routes.deployment(subject.id(), environment)
                    .flatMap(deployment -> routes.history(subject.id(), deployment.version()))
                    .map(row -> (JsonNode) RouteSnapshots.view(row.snapshot())).orElse(null);
            case PROFILE -> profiles.deployment(subject.id(), environment)
                    .flatMap(deployment -> profiles.history(subject.id(), deployment.version()))
                    .map(row -> (JsonNode) ProfileService.snapshot(row.snapshot())).orElse(null);
            case PLUGIN -> plugins.deployment(subject.id(), environment)
                    .flatMap(deployment -> plugins.history(subject.id(), deployment.version()))
                    .map(row -> (JsonNode) PluginService.snapshot(row.snapshot())).orElse(null);
        };
    }

    private Integer deployedVersion(Subject subject, String environment) {
        return switch (subject.type()) {
            case ROUTE -> routes.deployment(subject.id(), environment).map(RouteRepository.Deployment::version)
                    .orElse(null);
            case PROFILE -> profiles.deployment(subject.id(), environment).map(ProfileRepository.Deployment::version)
                    .orElse(null);
            case PLUGIN -> plugins.deployment(subject.id(), environment).map(PluginRepository.Deployment::version)
                    .orElse(null);
        };
    }

    private Subject subject(SubjectType type, long id, int version) {
        return switch (type) {
            case ROUTE -> {
                RouteRepository.Row row = routes.find(id).orElseThrow(() -> ApiException.notFound("Subtask yok: " + id));
                RouteRepository.HistoryRow history = routes.history(id, version)
                        .orElseThrow(() -> ApiException.notFound("Versiyon yok: v" + version));
                yield new Subject(type, id, row.maintask() + " / " + row.subtask(), row.projectId(), null,
                        RouteSnapshots.view(history.snapshot()));
            }
            case PROFILE -> {
                ProfileRepository.Row row = profiles.find(id).orElseThrow(() -> ApiException.notFound("Profil yok: " + id));
                ProfileRepository.HistoryRow history = profiles.history(id, version)
                        .orElseThrow(() -> ApiException.notFound("Versiyon yok: v" + version));
                yield new Subject(type, id, row.name(), null, row.name(), ProfileService.snapshot(history.snapshot()));
            }
            case PLUGIN -> {
                PluginRepository.Row row = plugins.find(id).orElseThrow(() -> ApiException.notFound("Eklenti yok: " + id));
                PluginRepository.HistoryRow history = plugins.history(id, version)
                        .orElseThrow(() -> ApiException.notFound("Versiyon yok: v" + version));
                yield new Subject(type, id, row.name(), null, null, PluginService.snapshot(history.snapshot()));
            }
        };
    }

    private View view(User user, PromotionRepository.Row row, boolean detail) {
        boolean approval = row.decidedBy() != null || "PENDING_APPROVAL".equals(row.status())
                || "CANCELLED".equals(row.status());
        Diff diff = null;
        List<AffectedRoute> affected = null;
        if (detail) {
            Subject subject = subject(row.type(), row.subjectId(), row.subjectVersion());
            JsonNode deployed = deployedSnapshot(subject, row.targetEnv());
            diff = new Diff(deployedVersion(subject, row.targetEnv()), Versions.changes(deployed, subject.snapshot()),
                    row.route() ? RouteSnapshots.foreignAdded(deployed, subject.snapshot()) : List.of(),
                    row.route() && RouteSnapshots.opensAnonymous(deployed, subject.snapshot()));
            affected = row.type() != SubjectType.PROFILE ? List.of() : profiles.usage(row.profileId()).stream()
                    .filter(usage -> usage.environment().equals(row.targetEnv()))
                    .map(usage -> new AffectedRoute(usage.routeId(), usage.maintask(), usage.subtask(), usage.version()))
                    .toList();
        }
        return new View(row.id(), row.type().name(), row.subjectId(), row.subjectName(),
                row.subjectVersion(), row.sourceEnv(), row.targetEnv(), row.action(), row.status(), approval,
                row.requestedBy(), row.requestedAt(), row.reason(), row.decidedBy(), row.decidedAt(),
                row.decisionNote(), row.probeStatus(), row.configVersion(), row.appliedAt(), row.failureReason(), diff,
                affected, allowedActions(user, row));
    }

    static List<String> allowedActions(User user, PromotionRepository.Row row) {
        List<String> actions = new ArrayList<>();
        boolean own = row.requestedBy().equals(user.username());
        switch (row.status()) {
            case "PENDING_APPROVAL" -> {
                if (user.has(Role.APPROVER) && !own) {
                    actions.add("APPROVE");
                    actions.add("REJECT");
                }
                if (own && user.has(Role.ROUTE_EDITOR)) {
                    actions.add("CANCEL");
                }
            }
            case "FAILED", "APPLYING" -> {
                if (user.has(Role.ROUTE_EDITOR)) {
                    actions.add("RETRY");
                }
            }
            default -> {
            }
        }
        return actions;
    }

    private static void fourEyes(User user, PromotionRepository.Row request) {
        if (request.requestedBy().equals(user.username())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FOUR_EYES",
                    "Kendi talebinizi onaylayamaz ya da reddedemezsiniz; başka bir onaylayıcı gerekli.");
        }
    }

    private static ApiException invalidState(PromotionRepository.Row request) {
        return ApiException.conflict("INVALID_STATE", "Talep bu işlem için uygun durumda değil: " + request.status());
    }

    private PromotionRepository.Row require(long id) {
        return promotions.find(id).orElseThrow(() -> ApiException.notFound("Talep yok: " + id));
    }
}
