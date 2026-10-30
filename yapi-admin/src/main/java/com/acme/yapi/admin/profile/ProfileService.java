package com.acme.yapi.admin.profile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.acme.yapi.admin.db.Versions;
import com.acme.yapi.admin.web.ApiException;
import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.naming.Names;
import com.acme.yapi.core.response.ResponseProfile;
import com.acme.yapi.core.validation.FieldError;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class ProfileService {

    private final ProfileRepository profiles;

    public ProfileService(ProfileRepository profiles) {
        this.profiles = profiles;
    }

    public record DeploymentView(String environment, int version, String activatedBy, Instant activatedAt) {
    }

    public record Summary(long id, String name, String description, int version, String updatedBy, Instant updatedAt,
                          List<DeploymentView> deployments) {
    }

    public record RouteRef(long routeId, String maintask, String subtask, int version) {
    }

    public record EnvironmentUsage(String environment, List<RouteRef> routes) {
    }

    public record Detail(long id, String name, String description, JsonNode definition, int version, String createdBy,
                         Instant createdAt, String updatedBy, Instant updatedAt, List<DeploymentView> deployments,
                         List<EnvironmentUsage> usage) {
    }

    public record Body(String name, String description, JsonNode definition, Integer version, String comment) {
    }

    public record Rollback(Integer restoreVersion, Integer version, String comment) {
    }

    public List<Summary> list() {
        List<ProfileRepository.Deployment> deployments = profiles.deployments();
        return profiles.list().stream()
                .map(row -> new Summary(row.id(), row.name(), row.description(), row.version(), row.updatedBy(),
                        row.updatedAt(), deployments.stream().filter(d -> d.profileId() == row.id())
                        .map(ProfileService::view).toList()))
                .toList();
    }

    public Detail get(long id) {
        ProfileRepository.Row row = require(id);
        Map<String, List<RouteRef>> byEnvironment = new LinkedHashMap<>();
        for (ProfileRepository.Usage usage : profiles.usage(id)) {
            byEnvironment.computeIfAbsent(usage.environment(), key -> new ArrayList<>())
                    .add(new RouteRef(usage.routeId(), usage.maintask(), usage.subtask(), usage.version()));
        }
        List<EnvironmentUsage> usage = new ArrayList<>();
        byEnvironment.forEach((environment, routes) -> usage.add(new EnvironmentUsage(environment, routes)));
        return new Detail(row.id(), row.name(), row.description(), row.definition(), row.version(), row.createdBy(),
                row.createdAt(), row.updatedBy(), row.updatedAt(),
                profiles.deployments(id).stream().map(ProfileService::view).toList(), usage);
    }

    @Transactional
    public Detail create(String user, Body body) {
        List<FieldError> errors = new ArrayList<>();
        if (!Names.isValidProfileName(body.name())) {
            errors.add(new FieldError("name", "1–100 karakter; harf ya da rakamla başlar, harf, rakam, _, . ve - içerir"));
        }
        errors.addAll(definitionErrors(body.definition()));
        if (!errors.isEmpty()) {
            throw ApiException.validation("Profil", errors);
        }
        long id;
        try {
            id = profiles.insert(body.name(), body.description(), body.definition(), user, body.comment());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("DUPLICATE", "Bu adla bir profil zaten var: " + body.name());
        }
        return get(id);
    }

    @Transactional
    public Detail update(String user, long id, Body body) {
        require(id);
        List<FieldError> errors = new ArrayList<>(definitionErrors(body.definition()));
        if (body.version() == null) {
            errors.add(new FieldError("version", "Okunan versiyon zorunlu"));
        }
        if (body.name() != null && !body.name().equals(require(id).name())) {
            errors.add(new FieldError("name", "Profil adı değiştirilemez"));
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation("Profil", errors);
        }
        write(user, id, body.version(), body.description(), body.definition(), "UPDATE", body.comment());
        return get(id);
    }

    @Transactional
    public Detail rollback(String user, long id, Rollback body) {
        ProfileRepository.Row current = require(id);
        if (body.restoreVersion() == null) {
            throw ApiException.validation(List.of(new FieldError("restoreVersion", "Dönülecek versiyon zorunlu")));
        }
        ProfileRepository.HistoryRow target = profiles.history(id, body.restoreVersion())
                .orElseThrow(() -> ApiException.notFound("Versiyon yok: v" + body.restoreVersion()));
        int expected = body.version() == null ? current.version() : body.version();
        write(user, id, expected, Json.text(target.snapshot().get("description")), target.snapshot().get("definition"),
                "ROLLBACK", body.comment() == null ? "v" + body.restoreVersion() + " versiyonuna dönüldü" : body.comment());
        return get(id);
    }

    private void write(String user, long id, int expected, String description, JsonNode definition, String action,
                       String comment) {
        if (profiles.update(id, expected, description, definition, user, action, comment).isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "Profil arada başkası tarafından değiştirildi.",
                    List.of(), Map.of("currentVersion", require(id).version()));
        }
    }

    public List<Versions.Entry> history(long id) {
        require(id);
        List<ProfileRepository.Deployment> deployments = profiles.deployments(id);
        return profiles.history(id).stream()
                .map(row -> new Versions.Entry(row.version(), row.action(), row.changedBy(), row.changedAt(),
                        row.comment(), deployments.stream().filter(d -> d.version() == row.version())
                        .map(ProfileRepository.Deployment::environment).toList()))
                .toList();
    }

    public record VersionView(int version, String action, String changedBy, Instant changedAt, String comment,
                              JsonNode snapshot) {
    }

    public VersionView version(long id, int version) {
        ProfileRepository.HistoryRow row = profiles.history(id, version)
                .orElseThrow(() -> ApiException.notFound("Versiyon yok: v" + version));
        return new VersionView(row.version(), row.action(), row.changedBy(), row.changedAt(), row.comment(),
                snapshot(row.snapshot()));
    }

    public record Comparison(int from, int to, List<Versions.Change> changes) {
    }

    public Comparison compare(long id, int from, int to) {
        return new Comparison(from, to, Versions.changes(version(id, from).snapshot(), version(id, to).snapshot()));
    }

    /** The screen's view of a stored snapshot: what a version is, without bookkeeping columns. */
    public static ObjectNode snapshot(JsonNode stored) {
        ObjectNode view = Json.object();
        view.set("name", stored.get("name"));
        view.set("description", stored.get("description"));
        view.set("definition", stored.get("definition"));
        return view;
    }

    /** @param headers the sample answer's headers, for {@code ${backend.header.<ad>}}; may be left out */
    public record PreviewRequest(JsonNode definition, Integer status, JsonNode body, Map<String, String> headers) {
    }

    /**
     * @param matchedTemplate {@code success} or {@code error}
     * @param matchedCase     index of the case in that template whose conditions held; null when none did
     */
    public record PreviewResult(int status, JsonNode body, String outcome, String matchedTemplate, Integer matchedCase) {
    }

    /** The response the frontend would get for a sample backend answer; a null status stands for a timeout. */
    public PreviewResult preview(PreviewRequest request) {
        List<FieldError> errors = definitionErrors(request.definition());
        if (!errors.isEmpty()) {
            throw ApiException.validation("Profil", errors);
        }
        ResponseProfile profile = ResponseProfile.parse(request.definition()).profile();
        com.acme.yapi.core.response.Outcome outcome = request.status() == null
                ? com.acme.yapi.core.response.Outcome.TIMEOUT
                : com.acme.yapi.core.response.Outcome.fromBackendStatus(request.status());
        Map<String, String> headers = new HashMap<>();
        if (request.headers() != null) {
            request.headers().forEach((name, value) -> headers.put(Names.key(name), value));
        }
        ResponseProfile.Rendered rendered = profile.evaluate(new ResponseProfile.BackendResult(request.status(),
                request.body() == null ? Json.missing() : request.body(), outcome, "00000000-0000-7000-8000-000000000000",
                headers));
        return new PreviewResult(rendered.response().status(), rendered.response().body(), outcome.name(),
                rendered.template(), rendered.matchedCase());
    }

    public ProfileRepository.Row require(long id) {
        return profiles.find(id).orElseThrow(() -> ApiException.notFound("Profil yok: " + id));
    }

    private static List<FieldError> definitionErrors(JsonNode definition) {
        return ResponseProfile.parse(definition).errors().stream().map(error -> error.prefixed("definition")).toList();
    }

    private static DeploymentView view(ProfileRepository.Deployment deployment) {
        return new DeploymentView(deployment.environment(), deployment.version(), deployment.activatedBy(),
                deployment.activatedAt());
    }
}
