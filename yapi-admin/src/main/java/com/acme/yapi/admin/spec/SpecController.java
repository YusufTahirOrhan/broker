package com.acme.yapi.admin.spec;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.acme.yapi.admin.db.Versions;
import com.acme.yapi.admin.environment.Environment;
import com.acme.yapi.admin.environment.EnvironmentClient;
import com.acme.yapi.admin.environment.EnvironmentRepository;
import com.acme.yapi.admin.plugin.PluginService;
import com.acme.yapi.admin.profile.ProfileRepository;
import com.acme.yapi.admin.project.ProjectRepository;
import com.acme.yapi.admin.route.RouteRepository;
import com.acme.yapi.admin.route.RouteService;
import com.acme.yapi.admin.security.CurrentUser;
import com.acme.yapi.admin.security.Role;
import com.acme.yapi.admin.security.User;
import com.acme.yapi.admin.web.ApiException;
import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.sample.SampleTemplate;
import com.acme.yapi.core.spec.OpenApiSpec;
import com.acme.yapi.core.spec.SpecProposal;
import com.acme.yapi.core.route.RouteDefinitionParser;
import com.acme.yapi.core.testcall.TestCallRequest;
import com.acme.yapi.core.validation.FieldError;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Spec comparison, drafting from a pasted request, test calls and OPTIONS probes; all go to an environment. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class SpecController {

    private static final Logger audit = LoggerFactory.getLogger("yapi.audit");

    private final ProjectRepository projects;
    private final RouteRepository routes;
    private final RouteService routeService;
    private final ProfileRepository profiles;
    private final PluginService plugins;
    private final EnvironmentRepository environments;
    private final EnvironmentClient client;
    private final CurrentUser currentUser;

    public SpecController(ProjectRepository projects, RouteRepository routes, RouteService routeService,
                          ProfileRepository profiles, PluginService plugins, EnvironmentRepository environments,
                          EnvironmentClient client, CurrentUser currentUser) {
        this.projects = projects;
        this.routes = routes;
        this.routeService = routeService;
        this.profiles = profiles;
        this.plugins = plugins;
        this.environments = environments;
        this.client = client;
        this.currentUser = currentUser;
    }

    public record RouteRef(long id, String subtask, int version, String pathTemplate, String specFingerprint) {
    }

    /** {@code group}: NEW, CHANGED, MISSING or SAME. */
    public record Item(String group, String httpMethod, String path, String operationId, String summary,
                       String fingerprint, JsonNode specOperation, RouteRef route, JsonNode proposal,
                       List<Versions.Change> changes) {
    }

    public record Comparison(String environment, List<Item> items) {
    }

    /** Operations matched to subtasks by method + path, never by operationId. */
    @GetMapping("/spec")
    public Comparison spec(@PathVariable long projectId, @RequestParam(name = "env", required = false) String code) {
        currentUser.get();
        ProjectRepository.Row project = project(projectId);
        Environment environment = environment(code);
        List<OpenApiSpec.Operation> operations = OpenApiSpec.operations(fetchSpec(project, environment));
        Map<String, RouteRepository.Row> byKey = new LinkedHashMap<>();
        for (RouteRepository.Row route : routes.list(projectId, "ACTIVE", null)) {
            byKey.put(matchKey(route.httpMethod(), route.pathTemplate()), route);
        }
        List<Item> items = new ArrayList<>();
        for (OpenApiSpec.Operation operation : operations) {
            RouteRepository.Row route = byKey.remove(matchKey(operation.httpMethod(), operation.path()));
            if (route == null) {
                items.add(item("NEW", operation, null, SpecProposal.propose(operation), List.of()));
            } else if (route.specFingerprint() != null && !route.specFingerprint().equals(operation.fingerprint())) {
                items.add(item("CHANGED", operation, route, null,
                        Versions.changes(route.specOperation(), operation.routing())));
            } else {
                items.add(item("SAME", operation, route, null, List.of()));
            }
        }
        for (RouteRepository.Row route : byKey.values()) {
            items.add(new Item("MISSING", route.httpMethod(), route.pathTemplate(), null, null, null, null, ref(route),
                    null, List.of()));
        }
        List<String> order = List.of("MISSING", "CHANGED", "NEW", "SAME");
        items.sort(Comparator.comparing((Item item) -> order.indexOf(item.group())).thenComparing(Item::path)
                .thenComparing(Item::httpMethod));
        return new Comparison(environment.code(), items);
    }

    public record Operation(String httpMethod, String path) {
    }

    public record SampleRequest(String text, String environment, Operation operation) {
    }

    /** Fields from a pasted body or "Copy as cURL"; with an operation, mapped to its parameters by name. */
    @PostMapping("/templates/from-sample")
    public SampleTemplate.Result fromSample(@PathVariable long projectId, @RequestBody SampleRequest body) {
        currentUser.get().require(Role.ROUTE_EDITOR);
        ProjectRepository.Row project = project(projectId);
        OpenApiSpec.Operation operation = null;
        if (body.operation() != null) {
            Environment environment = environment(body.environment());
            String key = matchKey(body.operation().httpMethod(), body.operation().path());
            operation = OpenApiSpec.operations(fetchSpec(project, environment)).stream()
                    .filter(candidate -> matchKey(candidate.httpMethod(), candidate.path()).equals(key))
                    .findFirst()
                    .orElseThrow(() -> ApiException.validation(List.of(new FieldError("operation",
                            "Spec'te böyle bir operasyon yok: " + key))));
        }
        try {
            return SampleTemplate.draft(body.text(), operation);
        } catch (IllegalArgumentException e) {
            throw ApiException.validation(List.of(new FieldError("text", e.getMessage())));
        }
    }

    /**
     * A real call with an unsaved definition, through the environment's broker. Only where test calls are allowed;
     * every call is written to the audit log.
     */
    @PostMapping("/test")
    public JsonNode test(@PathVariable long projectId, @RequestBody RouteService.Trial trial) {
        User user = currentUser.get().require(Role.ROUTE_EDITOR);
        ProjectRepository.Row project = project(projectId);
        Environment environment = environment(trial.environment());
        if (!environment.testCallsAllowed()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "TEST_NOT_ALLOWED",
                    environment.code() + " ortamı test çağrısına kapalı.");
        }
        routeService.checkTrialDefinition(trial);
        TestCallRequest.Profile profile = null;
        if (trial.responseProfileId() != null) {
            profile = testProfile(trial.responseProfileId(), environment);
        }
        // the plugins as the environment runs them (the latest version where one is not there yet)
        List<TestCallRequest.PluginRef> chosen = plugins.testRefs(project.maintask(),
                RouteDefinitionParser.parse(trial.definition()).definition(), environment.code());
        TestCallRequest call = new TestCallRequest(project.maintask(), null, trial.httpMethod(), trial.pathTemplate(),
                trial.definition(), profile, trial.sample(), trial.headers(),
                trial.userId() == null ? user.username() : trial.userId(), trial.permission(), user.username(),
                chosen);
        JsonNode result = client.call(environment, "POST", "/admin/v1/test", call);
        audit.atInfo()
                .addKeyValue("operation", "test")
                .addKeyValue("actor", user.username())
                .addKeyValue("environment", environment.code())
                .addKeyValue("target", project.maintask() + " " + trial.httpMethod() + " " + trial.pathTemplate())
                .addKeyValue("outcome", Json.stringField(result, "outcome"))
                .log("Test çağrısı {} {} {}", environment.code(), project.maintask(), trial.pathTemplate());
        return result;
    }

    public record ProbeRequest(String environment, String httpMethod, String pathTemplate,
                               Map<String, String> pathVariables) {
    }

    @PostMapping("/probe")
    public JsonNode probe(@PathVariable long projectId, @RequestBody ProbeRequest body) {
        User user = currentUser.get().require(Role.ROUTE_EDITOR);
        ProjectRepository.Row project = project(projectId);
        Environment environment = environment(body.environment());
        ObjectNode request = Json.object();
        request.put("maintask", project.maintask());
        request.put("httpMethod", body.httpMethod());
        request.put("pathTemplate", body.pathTemplate());
        request.set("pathVariables", Json.toTree(body.pathVariables() == null ? Map.of() : body.pathVariables()));
        request.put("actor", user.username());
        return client.call(environment, "POST", "/admin/v1/probe", request);
    }

    /** The profile as the environment runs it, or its latest version when it is not there yet. */
    private TestCallRequest.Profile testProfile(long profileId, Environment environment) {
        ProfileRepository.Row profile = profiles.find(profileId)
                .orElseThrow(() -> ApiException.validation(List.of(new FieldError("responseProfileId", "Profil yok"))));
        Optional<ProfileRepository.Deployment> deployed = profiles.deployment(profileId, environment.code());
        if (deployed.isPresent()) {
            JsonNode snapshot = profiles.history(profileId, deployed.get().version()).orElseThrow().snapshot();
            return new TestCallRequest.Profile(profile.name(), deployed.get().version(), snapshot.get("definition"));
        }
        return new TestCallRequest.Profile(profile.name(), profile.version(), profile.definition());
    }

    private JsonNode fetchSpec(ProjectRepository.Row project, Environment environment) {
        ProjectRepository.EnvironmentRow settings = projects.environment(project.id(), environment.code())
                .orElseThrow(() -> ApiException.validation(List.of(new FieldError("env",
                        "Proje " + environment.code() + " ortamında tanımlı değil"))));
        return client.call(environment, "GET", "/admin/v1/projects/" + project.maintask() + "/spec?path="
                + URLEncoder.encode(settings.specPath(), StandardCharsets.UTF_8), null);
    }

    private ProjectRepository.Row project(long id) {
        return projects.find(id).orElseThrow(() -> ApiException.notFound("Proje yok: " + id));
    }

    private Environment environment(String code) {
        return code == null || code.isBlank() ? environments.first() : environments.require(code);
    }

    /** Method + path with variable names ignored: {@code /orders/{id}} and {@code /orders/{orderId}} match. */
    static String matchKey(String method, String path) {
        return (method == null ? "" : method.toUpperCase(Locale.ROOT)) + " "
                + OpenApiSpec.normalizePath(path).replaceAll("\\{[^}/]*}", "{}");
    }

    private static Item item(String group, OpenApiSpec.Operation operation, RouteRepository.Row route,
                             JsonNode proposal, List<Versions.Change> changes) {
        return new Item(group, operation.httpMethod(), operation.path(), operation.operationId(), operation.summary(),
                operation.fingerprint(), operation.routing(), route == null ? null : ref(route), proposal, changes);
    }

    private static RouteRef ref(RouteRepository.Row route) {
        return new RouteRef(route.id(), route.subtask(), route.version(), route.pathTemplate(), route.specFingerprint());
    }
}
