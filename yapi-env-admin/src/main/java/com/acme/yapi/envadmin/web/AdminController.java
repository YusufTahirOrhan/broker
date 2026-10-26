package com.acme.yapi.envadmin.web;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.naming.Names;
import com.acme.yapi.core.response.ResponseProfile;
import com.acme.yapi.core.route.AlarmRoute;
import com.acme.yapi.core.route.HttpMethodName;
import com.acme.yapi.core.route.RouteValidator;
import com.acme.yapi.core.testcall.TestCallRequest;
import com.acme.yapi.core.validation.FieldError;
import com.acme.yapi.envadmin.config.EnvAdminProperties;
import com.acme.yapi.envadmin.runtime.ApplyResult;
import com.acme.yapi.envadmin.runtime.RuntimeStore;
import com.acme.yapi.envadmin.signal.SignalClient;
import com.acme.yapi.envadmin.upstream.ProbeResult;
import com.acme.yapi.envadmin.upstream.UpstreamClient;
import com.acme.yapi.envadmin.upstream.UpstreamPaths;

import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.JsonNode;

/** The Admin API contract of the design document ("Ortam Admin API sözleşmesi"). */
@RestController
@RequestMapping("/admin/v1")
public class AdminController {

    static final String DEFAULT_SPEC_PATH = "/v3/api-docs";

    private final RuntimeStore store;
    private final SignalClient signals;
    private final UpstreamClient upstream;
    private final EnvAdminProperties properties;

    public AdminController(RuntimeStore store, SignalClient signals, UpstreamClient upstream,
                           EnvAdminProperties properties) {
        this.store = store;
        this.signals = signals;
        this.upstream = upstream;
        this.properties = properties;
    }

    /** {@code configVersion} is the environment's version after the call; {@code alreadyApplied} marks a replay. */
    public record ApplyResponse(boolean applied, long configVersion, boolean alreadyApplied, boolean signalled) {
    }

    @PutMapping("/routes/{maintask}/{subtask}")
    public ApplyResponse applyRoute(@PathVariable String maintask, @PathVariable String subtask,
                                    @RequestBody Requests.RouteApply body, HttpServletRequest http) {
        List<FieldError> errors = new ArrayList<>();
        checkMaintask(maintask, errors);
        checkSubtask(subtask, errors);
        checkPromotion(body.promotionId(), errors);
        checkActor(body.actor(), errors);
        if (body.definitionId() == null || body.definitionId() < 1) {
            errors.add(new FieldError("definitionId", "Zorunlu"));
        }
        if (body.version() == null || body.version() < 1) {
            errors.add(new FieldError("version", "1 ya da daha büyük olmalı"));
        }
        List<String> permissions = body.permissions() == null ? List.of() : body.permissions();
        for (int i = 0; i < permissions.size(); i++) {
            String permission = permissions.get(i);
            if (permission == null || permission.isBlank() || permission.length() > Names.PERMISSION_MAX_LENGTH) {
                errors.add(new FieldError("permissions[" + i + "]", "Yetki adı boş ya da çok uzun"));
            }
        }
        boolean anonymous = Boolean.TRUE.equals(body.anonymous());
        if (!anonymous && permissions.isEmpty()) {
            errors.add(new FieldError("permissions", "Anonim olmayan route en az bir yetki ister"));
        }
        if (body.responseProfile() != null && !Names.isValidProfileName(body.responseProfile())) {
            errors.add(new FieldError("responseProfile", "Geçersiz profil adı"));
        }
        errors.addAll(RouteValidator.validate(body.httpMethod(), body.pathTemplate(), body.definition(),
                RouteValidator.Context.anyHeader()));
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        ApplyResult result = store.applyRoute(new RuntimeStore.RouteSnapshot(maintask, subtask, body.definitionId(),
                body.version(), body.httpMethod(), body.pathTemplate(), List.copyOf(permissions), anonymous,
                body.responseProfile(), body.definition(), body.actor(), body.promotionId()));
        ApplyResponse response = respond(result);
        Audit.record(http, "route.apply", body.actor(), body.promotionId(),
                maintask + "/" + subtask + " v" + body.version(), describe(response));
        return response;
    }

    @PutMapping("/response-profiles/{name}")
    public ApplyResponse applyProfile(@PathVariable String name, @RequestBody Requests.ProfileApply body,
                                      HttpServletRequest http) {
        List<FieldError> errors = new ArrayList<>();
        if (!Names.isValidProfileName(name)) {
            errors.add(new FieldError("name", "Geçersiz profil adı"));
        }
        checkPromotion(body.promotionId(), errors);
        checkActor(body.actor(), errors);
        if (body.profileId() == null || body.profileId() < 1) {
            errors.add(new FieldError("profileId", "Zorunlu"));
        }
        if (body.version() == null || body.version() < 1) {
            errors.add(new FieldError("version", "1 ya da daha büyük olmalı"));
        }
        ResponseProfile.parse(body.definition()).errors()
                .forEach(error -> errors.add(error.prefixed("definition")));
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        ApplyResult result = store.applyProfile(new RuntimeStore.ProfileSnapshot(name, body.profileId(),
                body.version(), body.definition(), body.actor(), body.promotionId()));
        ApplyResponse response = respond(result);
        Audit.record(http, "profile.apply", body.actor(), body.promotionId(), name + " v" + body.version(),
                describe(response));
        return response;
    }

    @PostMapping("/routes/{maintask}/{subtask}/disable")
    public ApplyResponse disable(@PathVariable String maintask, @PathVariable String subtask,
                                 @RequestBody Requests.Disable body, HttpServletRequest http) {
        List<FieldError> errors = new ArrayList<>();
        checkPromotion(body.promotionId(), errors);
        checkActor(body.actor(), errors);
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        ApplyResponse response = respond(store.disableRoute(maintask, subtask, body.promotionId(), body.actor()));
        Audit.record(http, "route.disable", body.actor(), body.promotionId(), maintask + "/" + subtask,
                describe(response));
        return response;
    }

    public record ProjectResponse(String maintask, String baseUrl, boolean enabled, AlarmRoute alarmRoute,
                                  boolean changed, long configVersion, boolean signalled) {
    }

    @PutMapping("/projects/{maintask}")
    public ProjectResponse applyProject(@PathVariable String maintask, @RequestBody Requests.ProjectApply body,
                                        HttpServletRequest http) {
        List<FieldError> errors = new ArrayList<>();
        checkMaintask(maintask, errors);
        checkActor(body.actor(), errors);
        UpstreamPaths.baseUrlProblems(body.baseUrl()).forEach(problem -> errors.add(new FieldError("baseUrl", problem)));
        AlarmRoute alarm = body.alarmRoute();
        if (alarm != null && !Names.isValidMaintask(alarm.maintask())) {
            errors.add(new FieldError("alarmRoute.maintask", "Geçerli bir maintask olmalı"));
        }
        if (alarm != null && !Names.isValidSubtask(alarm.subtask())) {
            errors.add(new FieldError("alarmRoute.subtask", "Geçerli bir subtask olmalı"));
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        boolean enabled = body.enabled() == null || body.enabled();
        Optional<Long> version = store.upsertProject(maintask, body.baseUrl(), enabled, alarm);
        boolean signalled = version.isPresent() && signals.publishRoutesChanged(version.get());
        ProjectResponse response = new ProjectResponse(maintask, body.baseUrl(), enabled, alarm, version.isPresent(),
                version.orElseGet(() -> store.configVersion().version()), signalled);
        Audit.record(http, "project.apply", body.actor(), null, maintask,
                (response.changed() ? "changed" : "unchanged") + " v" + response.configVersion());
        return response;
    }

    public record Listing(String environment, long configVersion, Instant configUpdatedAt,
                          List<RuntimeStore.ProjectRow> projects, List<RuntimeStore.ProfileRow> profiles,
                          List<RuntimeStore.RouteRow> routes) {
    }

    /** Everything the environment serves, for drift checks against the central deployments. */
    @GetMapping("/routes")
    public Listing routes() {
        RuntimeStore.ConfigVersion version = store.configVersion();
        return new Listing(properties.environment(), version.version(), version.updatedAt(), store.projects(),
                store.profiles(), store.routes());
    }

    public record InstanceStatus(String instanceId, Long loadedVersion, Instant loadedAt, boolean upToDate) {
    }

    public record CacheStatus(String environment, long configVersion, Instant configUpdatedAt,
                              List<InstanceStatus> instances) {
    }

    @GetMapping("/cache-status")
    public CacheStatus cacheStatus() {
        RuntimeStore.ConfigVersion version = store.configVersion();
        List<InstanceStatus> instances = signals.instances().stream()
                .map(instance -> new InstanceStatus(instance.instanceId(), instance.loadedVersion(),
                        instance.loadedAt(),
                        instance.loadedVersion() != null && instance.loadedVersion() >= version.version()))
                .toList();
        return new CacheStatus(properties.environment(), version.version(), version.updatedAt(), instances);
    }

    /** The service's OpenAPI document; the host comes from the runtime DB, only the path from the caller. */
    @GetMapping(path = "/projects/{maintask}/spec", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> spec(@PathVariable String maintask,
                                       @RequestParam(name = "path", required = false) String path,
                                       HttpServletRequest http) {
        String specPath = path == null || path.isBlank() ? DEFAULT_SPEC_PATH : path;
        List<FieldError> errors = new ArrayList<>();
        UpstreamPaths.problems(specPath).forEach(problem -> errors.add(new FieldError("path", problem)));
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        String baseUrl = baseUrl(maintask);
        URI uri = UpstreamPaths.resolve(baseUrl, specPath);
        UpstreamClient.Fetched fetched;
        try {
            fetched = upstream.fetch(uri);
        } catch (UpstreamClient.UnreachableException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "UPSTREAM_ERROR", e.getMessage(), List.of(),
                    Map.of("url", uri.toString()));
        }
        if (fetched.status() < 200 || fetched.status() >= 300) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "UPSTREAM_ERROR",
                    "Servis spec için " + fetched.status() + " döndü; spec bu ortamda kapalı olabilir.", List.of(),
                    Map.of("url", uri.toString(), "upstreamStatus", fetched.status()));
        }
        if (fetched.tooLarge()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "UPSTREAM_ERROR", "Spec izin verilen boyuttan büyük.",
                    List.of(), Map.of("url", uri.toString()));
        }
        JsonNode spec = Json.tryParse(fetched.body());
        if (spec == null || !spec.isObject()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "UPSTREAM_ERROR", "Servisin döndüğü spec JSON değil.",
                    List.of(), Map.of("url", uri.toString()));
        }
        Audit.record(http, "spec.fetch", null, null, uri.toString(), "ok");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(fetched.body());
    }

    @PostMapping("/probe")
    public ProbeResult probe(@RequestBody Requests.Probe body, HttpServletRequest http) {
        List<FieldError> errors = new ArrayList<>();
        checkMaintask(body.maintask(), errors);
        Optional<HttpMethodName> method = HttpMethodName.parse(body.httpMethod());
        if (method.isEmpty()) {
            errors.add(new FieldError("httpMethod", "GET, POST, PUT, PATCH ya da DELETE olmalı"));
        }
        UpstreamPaths.problems(body.pathTemplate())
                .forEach(problem -> errors.add(new FieldError("pathTemplate", problem)));
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        ProbeResult result = upstream.probe(baseUrl(body.maintask()), method.get(), body.pathTemplate(),
                body.pathVariables());
        Audit.record(http, "probe", body.actor(), body.promotionId(),
                method.get() + " " + result.url(), result.result());
        return result;
    }

    /**
     * Forwards a test call to the broker's internal endpoint with the project's registered base URL. Disabled where
     * test calls are not allowed (PROD).
     */
    @PostMapping(path = "/test", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> test(@RequestBody TestCallRequest body, HttpServletRequest http) {
        EnvAdminProperties.Test test = properties.test();
        if (!test.enabled() || test.yapiUrl() == null || test.yapiUrl().isBlank()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "TEST_NOT_ALLOWED", "Bu ortam test çağrısına kapalı.");
        }
        List<FieldError> errors = new ArrayList<>();
        checkMaintask(body.maintask(), errors);
        if (body.sample() == null || !body.sample().isObject()) {
            errors.add(new FieldError("sample", "Örnek gövde bir JSON nesnesi olmalı"));
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        TestCallRequest forwarded = body.withBaseUrl(baseUrl(body.maintask()));
        Map<String, String> headers = new LinkedHashMap<>();
        if (test.secret() != null) {
            headers.put(TestCallRequest.SECRET_HEADER, test.secret());
        }
        URI uri = UpstreamPaths.resolve(test.yapiUrl(), TestCallRequest.PATH);
        UpstreamClient.Fetched fetched;
        try {
            fetched = upstream.post(uri, Json.writeBytes(forwarded), headers, test.timeout());
        } catch (UpstreamClient.UnreachableException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "YAPI_UNREACHABLE",
                    "Ortamın broker'ına ulaşılamadı: " + e.getMessage());
        }
        Audit.record(http, "test", body.actor(), null,
                body.maintask() + " " + body.httpMethod() + " " + body.pathTemplate(), "status " + fetched.status());
        return ResponseEntity.status(fetched.status()).contentType(MediaType.APPLICATION_JSON).body(fetched.body());
    }

    private String baseUrl(String maintask) {
        return store.project(maintask)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND",
                        "Proje bu ortamda tanımlı değil: " + maintask))
                .baseUrl();
    }

    private ApplyResponse respond(ApplyResult result) {
        return switch (result) {
            case ApplyResult.Applied applied ->
                    new ApplyResponse(true, applied.configVersion(), false,
                            signals.publishRoutesChanged(applied.configVersion()));
            case ApplyResult.AlreadyApplied replay -> new ApplyResponse(true, replay.configVersion(), true, false);
            case ApplyResult.Stale stale -> throw new ApiException(HttpStatus.CONFLICT, "STALE_PROMOTION",
                    "Ortamda daha yeni bir talep uygulanmış; bu talep yazılmadı.", List.of(),
                    Map.of("currentPromotionId", stale.currentPromotionId(), "configVersion", stale.configVersion()));
            case ApplyResult.Missing missing -> throw new ApiException(
                    "ROUTE_NOT_FOUND".equals(missing.code()) ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT,
                    missing.code(), missing.message());
        };
    }

    private static String describe(ApplyResponse response) {
        return (response.alreadyApplied() ? "replayed" : "applied") + " v" + response.configVersion();
    }

    private static void checkMaintask(String maintask, List<FieldError> errors) {
        if (!Names.isValidMaintask(maintask)) {
            errors.add(new FieldError("maintask", "Geçersiz maintask"));
        }
    }

    private static void checkSubtask(String subtask, List<FieldError> errors) {
        // names migrated from the old broker keep their spelling, so only the length is enforced here
        if (subtask == null || subtask.isBlank() || subtask.length() > Names.SUBTASK_MAX_LENGTH) {
            errors.add(new FieldError("subtask", "Geçersiz subtask"));
        }
    }

    private static void checkPromotion(Long promotionId, List<FieldError> errors) {
        if (promotionId == null || promotionId < 1) {
            errors.add(new FieldError("promotionId", "Zorunlu"));
        }
    }

    private static void checkActor(String actor, List<FieldError> errors) {
        if (actor == null || actor.isBlank() || actor.length() > 100) {
            errors.add(new FieldError("actor", "İşlemi yapan kullanıcı zorunlu"));
        }
    }
}
