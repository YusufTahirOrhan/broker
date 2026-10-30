package com.acme.yapi.admin.plugin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.acme.yapi.admin.config.AdminProperties;
import com.acme.yapi.admin.db.Versions;
import com.acme.yapi.admin.project.ProjectRepository;
import com.acme.yapi.admin.security.Role;
import com.acme.yapi.admin.security.User;
import com.acme.yapi.admin.web.ApiException;
import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.naming.Names;
import com.acme.yapi.core.plugin.Plugin;
import com.acme.yapi.core.plugin.PluginDefinition;
import com.acme.yapi.core.plugin.Plugins;
import com.acme.yapi.core.request.BackendRequest;
import com.acme.yapi.core.request.BackendRequestBuilder;
import com.acme.yapi.core.request.RequestBodyValidator;
import com.acme.yapi.core.request.RequestBuildException;
import com.acme.yapi.core.request.RequestContext;
import com.acme.yapi.core.request.RequestScope;
import com.acme.yapi.core.route.HttpMethodName;
import com.acme.yapi.core.route.PathTemplate;
import com.acme.yapi.core.route.RouteDefinition;
import com.acme.yapi.core.route.RouteDefinitionParser;
import com.acme.yapi.core.route.RouteValidator;
import com.acme.yapi.core.testcall.TestCallRequest;
import com.acme.yapi.core.testcall.TestCallResult;
import com.acme.yapi.core.validation.FieldError;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Plugins ("Eklentiler"): versioned like response profiles, with history, comparison and rollback, and promoted to
 * environments the same way. A {@code GLOBAL} plugin reaches every subtask, so only an APPROVER creates or changes
 * one (and opens its promotions).
 */
@Service
public class PluginService {

    private static final String PREVIEW_REQUEST_ID = "00000000-0000-7000-8000-000000000000";

    private final PluginRepository plugins;
    private final ProjectRepository projects;
    private final RouteValidator.Context readable;

    public PluginService(PluginRepository plugins, ProjectRepository projects, AdminProperties properties) {
        this.plugins = plugins;
        this.projects = projects;
        this.readable = RouteValidator.Context.of(properties.readableHeaders());
    }

    public record DeploymentView(String environment, int version, String activatedBy, Instant activatedAt) {
    }

    public record Summary(long id, String name, String description, String scope, List<String> projects, int version,
                          String updatedBy, Instant updatedAt, List<DeploymentView> deployments) {
    }

    public record Detail(long id, String name, String description, JsonNode definition, int version, String createdBy,
                         Instant createdAt, String updatedBy, Instant updatedAt, List<DeploymentView> deployments) {
    }

    public record Body(String name, String description, JsonNode definition, Integer version, String comment) {
    }

    public record Rollback(Integer restoreVersion, Integer version, String comment) {
    }

    public List<Summary> list() {
        List<PluginRepository.Deployment> deployments = plugins.deployments();
        return plugins.list().stream()
                .map(row -> new Summary(row.id(), row.name(), row.description(),
                        Json.stringField(row.definition(), "scope"), strings(row.definition().get("projects")),
                        row.version(), row.updatedBy(), row.updatedAt(), deployments.stream()
                        .filter(d -> d.pluginId() == row.id()).map(PluginService::view).toList()))
                .toList();
    }

    public Detail get(long id) {
        PluginRepository.Row row = require(id);
        return new Detail(row.id(), row.name(), row.description(), row.definition(), row.version(), row.createdBy(),
                row.createdAt(), row.updatedBy(), row.updatedAt(),
                plugins.deployments(id).stream().map(PluginService::view).toList());
    }

    @Transactional
    public Detail create(User user, Body body) {
        user.require(Role.ROUTE_EDITOR);
        List<FieldError> errors = new ArrayList<>();
        if (!Names.isValidProfileName(body.name())) {
            errors.add(new FieldError("name", "1–100 karakter; harf ya da rakamla başlar, harf, rakam, _, . ve - içerir"));
        }
        errors.addAll(definitionErrors(body.definition()));
        if (!errors.isEmpty()) {
            throw ApiException.validation("Eklenti", errors);
        }
        requireGlobalRole(user, null, body.definition());
        long id;
        try {
            id = plugins.insert(body.name(), body.description(), body.definition(), user.username(), body.comment());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("DUPLICATE", "Bu adla bir eklenti zaten var: " + body.name());
        }
        return get(id);
    }

    @Transactional
    public Detail update(User user, long id, Body body) {
        user.require(Role.ROUTE_EDITOR);
        PluginRepository.Row current = require(id);
        List<FieldError> errors = new ArrayList<>(definitionErrors(body.definition()));
        if (body.version() == null) {
            errors.add(new FieldError("version", "Okunan versiyon zorunlu"));
        }
        if (body.name() != null && !body.name().equals(current.name())) {
            errors.add(new FieldError("name", "Eklenti adı değiştirilemez"));
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation("Eklenti", errors);
        }
        requireGlobalRole(user, current.definition(), body.definition());
        write(user.username(), id, body.version(), body.description(), body.definition(), "UPDATE", body.comment());
        return get(id);
    }

    @Transactional
    public Detail rollback(User user, long id, Rollback body) {
        user.require(Role.ROUTE_EDITOR);
        PluginRepository.Row current = require(id);
        if (body.restoreVersion() == null) {
            throw ApiException.validation(List.of(new FieldError("restoreVersion", "Dönülecek versiyon zorunlu")));
        }
        PluginRepository.HistoryRow target = plugins.history(id, body.restoreVersion())
                .orElseThrow(() -> ApiException.notFound("Versiyon yok: v" + body.restoreVersion()));
        JsonNode definition = target.snapshot().get("definition");
        requireGlobalRole(user, current.definition(), definition);
        int expected = body.version() == null ? current.version() : body.version();
        write(user.username(), id, expected, Json.text(target.snapshot().get("description")), definition, "ROLLBACK",
                body.comment() == null ? "v" + body.restoreVersion() + " versiyonuna dönüldü" : body.comment());
        return get(id);
    }

    /** A plugin that is or becomes GLOBAL reaches every subtask: only an APPROVER writes it. */
    private static void requireGlobalRole(User user, JsonNode before, JsonNode after) {
        if (isGlobal(before) || isGlobal(after)) {
            user.require(Role.APPROVER);
        }
    }

    public static boolean isGlobal(JsonNode definition) {
        return PluginDefinition.Scope.GLOBAL.name().equals(Json.stringField(definition, "scope"));
    }

    private void write(String user, long id, int expected, String description, JsonNode definition, String action,
                       String comment) {
        if (plugins.update(id, expected, description, definition, user, action, comment).isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "Eklenti arada başkası tarafından değiştirildi.",
                    List.of(), Map.of("currentVersion", require(id).version()));
        }
    }

    public List<Versions.Entry> history(long id) {
        require(id);
        List<PluginRepository.Deployment> deployments = plugins.deployments(id);
        return plugins.history(id).stream()
                .map(row -> new Versions.Entry(row.version(), row.action(), row.changedBy(), row.changedAt(),
                        row.comment(), deployments.stream().filter(d -> d.version() == row.version())
                        .map(PluginRepository.Deployment::environment).toList()))
                .toList();
    }

    public record VersionView(int version, String action, String changedBy, Instant changedAt, String comment,
                              JsonNode snapshot) {
    }

    public VersionView version(long id, int version) {
        PluginRepository.HistoryRow row = plugins.history(id, version)
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

    /**
     * What the plugin does to a sample subtask: the request the broker would send (with the plugin applied after the
     * definition) and the response the frontend would get.
     *
     * @param request  a subtask as in {@code /routes/preview}: method, path, definition, sample body and headers
     * @param response a sample answer of the service: status, headers and body (the body as the frontend gets it,
     *                 after any profile)
     */
    public record PreviewRequest(JsonNode definition, PreviewSubtask request, PreviewAnswer response) {
    }

    public record PreviewSubtask(String maintask, String subtask, String httpMethod, String pathTemplate,
                                 JsonNode definition, JsonNode sample, Map<String, String> headers, String userId) {
    }

    public record PreviewAnswer(Integer status, Map<String, String> headers, JsonNode body) {
    }

    public record PreviewResult(TestCallResult.RequestView request, TestCallResult.ClientView response,
                                List<FieldError> errors) {
    }

    public PreviewResult preview(User user, PreviewRequest body) {
        List<FieldError> errors = new ArrayList<>(definitionErrors(body.definition()));
        PreviewSubtask subtask = body.request();
        if (subtask == null) {
            errors.add(new FieldError("request", "Örnek subtask zorunlu"));
        } else {
            RouteValidator.validate(subtask.httpMethod(), subtask.pathTemplate(), subtask.definition(), readable)
                    .forEach(error -> errors.add(error.prefixed("request")));
            if (subtask.sample() == null || !subtask.sample().isObject()) {
                errors.add(new FieldError("request.sample", "Örnek gövde bir JSON nesnesi olmalı"));
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation("Önizleme", errors);
        }
        Plugin plugin = new Plugin("önizleme", 0, PluginDefinition.parse(body.definition(), readable).definition());
        RouteDefinition definition = RouteDefinitionParser.parse(subtask.definition()).definition();
        ObjectNode sample = (ObjectNode) subtask.sample();
        RequestBodyValidator.Result validated = RequestBodyValidator.validate(sample, definition.request().fields());
        if (!validated.valid()) {
            return new PreviewResult(null, null, validated.errors().stream()
                    .map(error -> error.prefixed("request.sample")).toList());
        }
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (subtask.headers() != null) {
            headers.putAll(subtask.headers());
        }
        String userId = subtask.userId() == null ? user.username() : subtask.userId();
        RequestScope scope = new RequestScope("ÖNİZLEME", "00000000000000000000000000000000", subtask.maintask(),
                subtask.subtask(), null, headers.get("X-Clientip"), headers.get("X-User-Agent"), List.of(),
                Instant.now(), null, null);
        RequestContext context = new RequestContext(sample, validated.effectiveBody(), headers::get, readable, userId,
                null, PREVIEW_REQUEST_ID).withScope(scope);
        BackendRequest request;
        try {
            request = BackendRequestBuilder.build(HttpMethodName.parse(subtask.httpMethod()).orElseThrow(),
                    PathTemplate.parse(subtask.pathTemplate()), definition, "http://servis", context);
        } catch (RequestBuildException e) {
            return new PreviewResult(null, null, List.of(e.error().prefixed("request.sample")));
        }
        request = Plugins.applyRequest(request, List.of(plugin), definition.request(), context.resolver()).request();
        PreviewAnswer answer = body.response() == null ? new PreviewAnswer(200, Map.of(), Json.object())
                : body.response();
        Map<String, String> backendHeaders = new HashMap<>();
        if (answer.headers() != null) {
            answer.headers().forEach((name, value) -> backendHeaders.put(Names.key(name), value));
        }
        int status = answer.status() == null ? 200 : answer.status();
        Plugins.ResponseValues values = new Plugins.ResponseValues(status, backendHeaders,
                com.acme.yapi.core.response.Outcome.fromBackendStatus(status).name(), 0L, scope.receivedAt(), scope);
        Plugins.ResponseResult response = Plugins.applyResponse(Map.of(), answer.body(), List.of(plugin),
                Plugins.responseResolver(context.resolver(), values));
        return new PreviewResult(TestCallResult.RequestView.of(request),
                new TestCallResult.ClientView(status, response.body(), response.headers()), List.of());
    }

    /**
     * The plugins a subtask gets in an environment, as the environment runs them: the deployed version of each, or
     * the latest one where the plugin is not there yet (so a preview or test shows what promoting would bring).
     */
    public List<Plugin> forTrial(String maintask, RouteDefinition definition, String environment) {
        List<Plugin> all = new ArrayList<>();
        for (PluginRepository.Row row : plugins.list()) {
            Optional<PluginRepository.Deployment> deployed = plugins.deployment(row.id(), environment);
            int version = deployed.map(PluginRepository.Deployment::version).orElse(row.version());
            JsonNode stored = deployed.isPresent()
                    ? plugins.history(row.id(), version).map(history -> history.snapshot().get("definition"))
                    .orElse(row.definition())
                    : row.definition();
            PluginDefinition.Parsed parsed = PluginDefinition.parse(stored, RouteValidator.Context.anyHeader());
            if (parsed.valid()) {
                all.add(new Plugin(row.name(), version, parsed.definition()));
            }
        }
        return Plugins.select(all, maintask, definition);
    }

    /** {@link #forTrial} as the test call carries them. */
    public List<TestCallRequest.PluginRef> testRefs(String maintask, RouteDefinition definition, String environment) {
        List<TestCallRequest.PluginRef> refs = new ArrayList<>();
        for (Plugin plugin : forTrial(maintask, definition, environment)) {
            PluginRepository.Row row = plugins.findByName(plugin.name()).orElseThrow();
            JsonNode stored = plugins.history(row.id(), plugin.version())
                    .map(history -> history.snapshot().get("definition")).orElse(row.definition());
            refs.add(new TestCallRequest.PluginRef(plugin.name(), plugin.version(), stored));
        }
        return refs;
    }

    /** Names a subtask definition may list, checked when it is saved; empty when they all exist. */
    public List<FieldError> nameErrors(RouteDefinition definition) {
        List<FieldError> errors = new ArrayList<>();
        for (int i = 0; i < definition.plugins().size(); i++) {
            String name = definition.plugins().get(i);
            Optional<PluginRepository.Row> row = findByName(name);
            if (row.isEmpty()) {
                errors.add(new FieldError("definition.plugins[" + i + "]", "Böyle bir eklenti yok: " + name));
            } else if (!PluginDefinition.Scope.SUBTASK.name().equals(Json.stringField(row.get().definition(), "scope"))) {
                errors.add(new FieldError("definition.plugins[" + i + "]",
                        "Yalnızca SUBTASK kapsamlı eklenti seçilir; " + name + " kendiliğinden uygulanır"));
            }
        }
        for (int i = 0; i < definition.disabledPlugins().size(); i++) {
            String name = definition.disabledPlugins().get(i);
            if (findByName(name).isEmpty()) {
                errors.add(new FieldError("definition.disabledPlugins[" + i + "]", "Böyle bir eklenti yok: " + name));
            }
        }
        return errors;
    }

    /** By name, ignoring case, as the broker matches it. */
    public Optional<PluginRepository.Row> findByName(String name) {
        return plugins.list().stream().filter(row -> Names.sameName(row.name(), name)).findFirst();
    }

    public PluginRepository.Row require(long id) {
        return plugins.find(id).orElseThrow(() -> ApiException.notFound("Eklenti yok: " + id));
    }

    private List<FieldError> definitionErrors(JsonNode definition) {
        List<FieldError> errors = new ArrayList<>(PluginDefinition.parse(definition, readable).errors().stream()
                .map(error -> error.prefixed("definition")).toList());
        if (errors.isEmpty()) {
            List<String> listed = strings(definition.get("projects"));
            for (int i = 0; i < listed.size(); i++) {
                if (projects.findByMaintask(listed.get(i)).isEmpty()) {
                    errors.add(new FieldError("definition.projects[" + i + "]", "Kayıtlı bir proje değil: " + listed.get(i)));
                }
            }
        }
        return errors;
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(value -> values.add(Json.text(value)));
        }
        return values;
    }

    private static DeploymentView view(PluginRepository.Deployment deployment) {
        return new DeploymentView(deployment.environment(), deployment.version(), deployment.activatedBy(),
                deployment.activatedAt());
    }
}
