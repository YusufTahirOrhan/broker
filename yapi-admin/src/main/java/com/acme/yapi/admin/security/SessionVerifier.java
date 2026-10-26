package com.acme.yapi.admin.security;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.acme.yapi.admin.config.AdminProperties;
import com.acme.yapi.admin.environment.Environment;
import com.acme.yapi.admin.environment.EnvironmentClient;
import com.acme.yapi.admin.environment.EnvironmentRepository;
import com.acme.yapi.core.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Asks the session environment's Admin API ({@code yapi.admin.session.environment}, PROD by default) whether a
 * session of the login application is valid; the central admin reaches environments only through their Admin API.
 * Answers, valid or not, are kept for {@code cache-ttl}; a failed call is not kept.
 */
public class SessionVerifier {

    public record Verified(boolean valid, List<String> permissions) {

        static final Verified INVALID = new Verified(false, List.of());
    }

    private record Kept(Verified result, Instant until) {
    }

    private static final int MAX_KEPT = 10_000;

    private final EnvironmentClient client;
    private final EnvironmentRepository environments;
    private final AdminProperties.Session settings;
    private final Clock clock;
    private final Map<String, Kept> kept = new ConcurrentHashMap<>();

    public SessionVerifier(EnvironmentClient client, EnvironmentRepository environments, AdminProperties properties,
                           Clock clock) {
        this.client = client;
        this.environments = environments;
        this.settings = properties.session();
        this.clock = clock;
    }

    public Verified verify(String uid, String token, String clientIp, String userAgent) {
        String key = String.join("\n", uid, token, clientIp, userAgent);
        Instant now = clock.instant();
        Kept known = kept.get(key);
        if (known != null && known.until().isAfter(now)) {
            return known.result();
        }
        Verified result = ask(uid, token, clientIp, userAgent);
        if (kept.size() >= MAX_KEPT) {
            kept.values().removeIf(entry -> !entry.until().isAfter(now));
        }
        kept.put(key, new Kept(result, now.plus(settings.cacheTtl())));
        return result;
    }

    private Verified ask(String uid, String token, String clientIp, String userAgent) {
        Environment environment = environments.require(settings.environment());
        ObjectNode body = Json.object();
        body.put("uid", uid);
        body.put("token", token);
        body.put("clientIp", clientIp);
        body.put("userAgent", userAgent);
        JsonNode answer;
        try {
            EnvironmentClient.Response response = client.send(environment, "POST", "/admin/v1/sessions/verify", body);
            if (!response.ok()) {
                throw EnvironmentClient.error(environment, response);
            }
            answer = response.body();
        } catch (EnvironmentClient.UnreachableException | EnvironmentClient.NoAnswerException e) {
            throw EnvironmentClient.unreachable(environment, e);
        }
        if (answer == null || !Json.isTrue(answer.get("valid"))) {
            return Verified.INVALID;
        }
        List<String> permissions = new ArrayList<>();
        JsonNode list = answer.get("permissions");
        if (list != null && list.isArray()) {
            list.forEach(permission -> {
                if (Json.isString(permission)) {
                    permissions.add(permission.stringValue());
                }
            });
        }
        return new Verified(true, List.copyOf(permissions));
    }
}
