package com.acme.yapi.admin.environment;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.acme.yapi.admin.config.AdminProperties;
import com.acme.yapi.admin.security.CurrentUser;
import com.acme.yapi.admin.security.Role;
import com.acme.yapi.admin.security.User;
import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.rules.DefinitionRules;

import tools.jackson.databind.JsonNode;

/** Session and environment endpoints the screens call at start. */
@RestController
@RequestMapping("/api/v1")
public class EnvironmentController {

    private final EnvironmentRepository environments;
    private final EnvironmentClient client;
    private final CurrentUser currentUser;
    private final AdminProperties properties;

    public EnvironmentController(EnvironmentRepository environments, EnvironmentClient client, CurrentUser currentUser,
                                 AdminProperties properties) {
        this.environments = environments;
        this.client = client;
        this.currentUser = currentUser;
        this.properties = properties;
    }

    public record Me(String username, String displayName, List<Role> roles) {
    }

    @GetMapping("/me")
    public Me me() {
        User user = currentUser.get();
        return new Me(user.username(), user.displayName(), user.roles().stream().sorted().toList());
    }

    public record EnvironmentView(String code, String name, int sortOrder, boolean requiresApproval,
                                  boolean testCallsAllowed) {
    }

    @GetMapping("/environments")
    public List<EnvironmentView> environments() {
        currentUser.get();
        return environments.all().stream()
                .map(environment -> new EnvironmentView(environment.code(), environment.name(),
                        environment.sortOrder(), environment.requiresApproval(), environment.testCallsAllowed()))
                .toList();
    }

    public record InstanceView(String instanceId, Long loadedVersion, Instant loadedAt, boolean upToDate,
                               boolean lagging) {
    }

    public record StatusView(String environment, long configVersion, Instant configUpdatedAt, int instanceCount,
                             int upToDateCount, List<InstanceView> instances) {
    }

    /** "3/3 instance v43'te"; an instance behind for longer than a minute is {@code lagging}. */
    @GetMapping("/environments/{code}/status")
    public StatusView status(@PathVariable String code) {
        currentUser.get();
        Environment environment = environments.require(code);
        JsonNode status = client.call(environment, "GET", "/admin/v1/cache-status", null);
        long configVersion = status.get("configVersion").longValue();
        Instant updatedAt = instant(status.get("configUpdatedAt"));
        Duration lagWarning = properties.environments().lagWarning();
        List<InstanceView> instances = new ArrayList<>();
        for (JsonNode instance : status.path("instances")) {
            JsonNode loaded = instance.get("loadedVersion");
            Long loadedVersion = loaded == null || loaded.isNull() ? null : loaded.longValue();
            boolean upToDate = loadedVersion != null && loadedVersion >= configVersion;
            boolean lagging = !upToDate && updatedAt != null
                    && Duration.between(updatedAt, Instant.now()).compareTo(lagWarning) > 0;
            instances.add(new InstanceView(Json.stringField(instance, "instanceId"), loadedVersion,
                    instant(instance.get("loadedAt")), upToDate, lagging));
        }
        int upToDate = (int) instances.stream().filter(InstanceView::upToDate).count();
        return new StatusView(environment.code(), configVersion, updatedAt, instances.size(), upToDate, instances);
    }

    @GetMapping("/meta/definition-rules")
    public JsonNode definitionRules() {
        currentUser.get();
        return DefinitionRules.describe(properties.readableHeaders());
    }

    private static Instant instant(JsonNode node) {
        String text = Json.text(node);
        return text == null ? null : Instant.parse(text);
    }
}
