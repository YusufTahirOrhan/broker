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
import com.acme.yapi.admin.profile.ProfileRepository;
import com.acme.yapi.admin.profile.ProfileService;
import com.acme.yapi.admin.project.ProjectRepository;
import com.acme.yapi.admin.route.RouteRepository;
import com.acme.yapi.admin.route.RouteSnapshots;
import com.acme.yapi.admin.security.Role;
import com.acme.yapi.admin.security.User;
import com.acme.yapi.admin.web.ApiException;
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
    private final ProjectRepository projects;
    private final EnvironmentRepository environments;
    private final AuthorizationService authorization;

    public PromotionService(PromotionRepository promotions, PromotionApplier applier, RouteRepository routes,
                            ProfileRepository profiles, ProjectRepository projects, EnvironmentRepository environments,
                            AuthorizationService authorization) {
        this.promotions = promotions;
        this.applier = applier;
        this.routes = routes;
        this.profiles = profiles;
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
    private record Subject(boolean route, long id, String name, Long projectId, String profileName, JsonNode snapshot) {
    }

    public View create(User user, Create body) {
        user.require(Role.ROUTE_EDITOR);
        List<FieldError> errors = new ArrayList<>();
        boolean route = "ROUTE".equals(body.subjectType());
        if (!route && !"PROFILE".equals(body.subjectType())) {
            errors.add(new FieldError("subjectType", "ROUTE ya da PROFILE olmalı"));
        }
        if (body.subjectId() == null) {
            errors.add(new FieldError("subjectId", "Zorunlu"));
        }
        if (body.action() == null || !(route ? ROUTE_ACTIONS : PROFILE_ACTIONS).contains(body.action())) {
            errors.add(new FieldError("action", route ? "DEPLOY, DISABLE, ENABLE ya da ROLLBACK olmalı"
                    : "Profilde yalnızca DEPLOY ve ROLLBACK"));
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
        Subject subject = subject(route, body.subjectId(), version);
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
            id = promotions.insert(route, subject.id(), version, source.map(Environment::code).orElse(null),
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
                checks.add(new Check("SOURCE_ACTIVE", active, "v" + version + " " + source.get().code() + "'"
                        + (active ? "te aktif" : "te aktif değil; yalnızca bir önceki ortamda aktif versiyon taşınır")));
            }
        }
        Optional<Long> open = promotions.open(subject.route(), subject.id(), target.code());
        checks.add(new Check("NO_OPEN_REQUEST", open.isEmpty(), open.isEmpty() ? "Açık talep yok"
                : "Aynı hedef için açık talep var: #" + open.get()));
        if (rollback) {
            boolean applied = promotions.previouslyApplied(subject.route(), subject.id(), version, target.code());
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
        }
        return checks;
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
                    ? "Yetkiler " + target.code() + " yetkilendirme servisinde tanımlı"
                    : target.code() + " yetkilendirme servisinde yok: " + String.join(", ", missing));
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

    private boolean activeIn(Subject subject, int version, String environment) {
        if (subject.route()) {
            return routes.deployment(subject.id(), environment)
                    .filter(deployment -> deployment.version() == version && "ACTIVE".equals(deployment.status()))
                    .isPresent();
        }
        return profiles.deployment(subject.id(), environment).filter(deployment -> deployment.version() == version)
                .isPresent();
    }

    /** Anonymous access needs approval in every environment: the version is anonymous and what runs there is not. */
    private boolean opensAnonymous(Subject subject, Environment target) {
        return RouteSnapshots.opensAnonymous(deployedSnapshot(subject, target.code()), subject.snapshot());
    }

    private JsonNode deployedSnapshot(Subject subject, String environment) {
        if (subject.route()) {
            return routes.deployment(subject.id(), environment)
                    .flatMap(deployment -> routes.history(subject.id(), deployment.version()))
                    .map(row -> (JsonNode) RouteSnapshots.view(row.snapshot())).orElse(null);
        }
        return profiles.deployment(subject.id(), environment)
                .flatMap(deployment -> profiles.history(subject.id(), deployment.version()))
                .map(row -> (JsonNode) ProfileService.snapshot(row.snapshot())).orElse(null);
    }

    private Integer deployedVersion(Subject subject, String environment) {
        return subject.route()
                ? routes.deployment(subject.id(), environment).map(RouteRepository.Deployment::version).orElse(null)
                : profiles.deployment(subject.id(), environment).map(ProfileRepository.Deployment::version).orElse(null);
    }

    private Subject subject(boolean route, long id, int version) {
        if (route) {
            RouteRepository.Row row = routes.find(id).orElseThrow(() -> ApiException.notFound("Subtask yok: " + id));
            RouteRepository.HistoryRow history = routes.history(id, version)
                    .orElseThrow(() -> ApiException.notFound("Versiyon yok: v" + version));
            return new Subject(true, id, row.maintask() + " / " + row.subtask(), row.projectId(), null,
                    RouteSnapshots.view(history.snapshot()));
        }
        ProfileRepository.Row row = profiles.find(id).orElseThrow(() -> ApiException.notFound("Profil yok: " + id));
        ProfileRepository.HistoryRow history = profiles.history(id, version)
                .orElseThrow(() -> ApiException.notFound("Versiyon yok: v" + version));
        return new Subject(false, id, row.name(), null, row.name(), ProfileService.snapshot(history.snapshot()));
    }

    private View view(User user, PromotionRepository.Row row, boolean detail) {
        boolean approval = row.decidedBy() != null || "PENDING_APPROVAL".equals(row.status())
                || "CANCELLED".equals(row.status());
        Diff diff = null;
        List<AffectedRoute> affected = null;
        if (detail) {
            Subject subject = subject(row.route(), row.subjectId(), row.subjectVersion());
            JsonNode deployed = deployedSnapshot(subject, row.targetEnv());
            diff = new Diff(deployedVersion(subject, row.targetEnv()), Versions.changes(deployed, subject.snapshot()),
                    row.route() ? RouteSnapshots.foreignAdded(deployed, subject.snapshot()) : List.of(),
                    row.route() && RouteSnapshots.opensAnonymous(deployed, subject.snapshot()));
            affected = row.route() ? List.of() : profiles.usage(row.profileId()).stream()
                    .filter(usage -> usage.environment().equals(row.targetEnv()))
                    .map(usage -> new AffectedRoute(usage.routeId(), usage.maintask(), usage.subtask(), usage.version()))
                    .toList();
        }
        return new View(row.id(), row.route() ? "ROUTE" : "PROFILE", row.subjectId(), row.subjectName(),
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
