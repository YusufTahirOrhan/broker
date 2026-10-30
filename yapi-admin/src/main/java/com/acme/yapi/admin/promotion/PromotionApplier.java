package com.acme.yapi.admin.promotion;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.acme.yapi.admin.environment.Environment;
import com.acme.yapi.admin.environment.EnvironmentClient;
import com.acme.yapi.admin.environment.EnvironmentRepository;
import com.acme.yapi.admin.plugin.PluginRepository;
import com.acme.yapi.admin.profile.ProfileRepository;
import com.acme.yapi.admin.route.RouteRepository;
import com.acme.yapi.admin.route.RouteSnapshots;
import com.acme.yapi.core.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Applies an APPLYING request to its target environment: OPTIONS probe first (routes, except DISABLE), then the
 * snapshot through the Admin API with the request's ID as {@code promotionId}. The environment writes it with one
 * idempotent statement, so a retried request never writes twice and a delayed old one never overwrites a newer one.
 */
@Component
public class PromotionApplier {

    private static final Logger log = LoggerFactory.getLogger(PromotionApplier.class);

    private final PromotionRepository promotions;
    private final RouteRepository routes;
    private final ProfileRepository profiles;
    private final PluginRepository plugins;
    private final EnvironmentRepository environments;
    private final EnvironmentClient client;
    private final TransactionTemplate transactions;

    public PromotionApplier(PromotionRepository promotions, RouteRepository routes, ProfileRepository profiles,
                            PluginRepository plugins, EnvironmentRepository environments, EnvironmentClient client,
                            TransactionTemplate transactions) {
        this.promotions = promotions;
        this.routes = routes;
        this.profiles = profiles;
        this.plugins = plugins;
        this.environments = environments;
        this.client = client;
        this.transactions = transactions;
    }

    public void apply(long id, String actor) {
        PromotionRepository.Row request = promotions.find(id).orElseThrow();
        Environment target = environments.require(request.targetEnv());
        try {
            switch (request.type()) {
                case ROUTE -> applyRoute(request, target, actor);
                case PROFILE -> applyProfile(request, target, actor);
                case PLUGIN -> applyPlugin(request, target, actor);
            }
        } catch (EnvironmentClient.UnreachableException e) {
            promotions.failed(id, e.getMessage() + "; hedef ortamda hiçbir şey değişmedi.");
        } catch (EnvironmentClient.NoAnswerException e) {
            promotions.unanswered(id, e.getMessage() + "; uygulanmış olabilir, yeniden denenebilir.");
        } catch (RuntimeException e) {
            log.error("Talep uygulanırken beklenmeyen hata (#{})", id, e);
            promotions.failed(id, "Beklenmeyen hata: " + e.getMessage());
        }
    }

    private void applyRoute(PromotionRepository.Row request, Environment target, String actor)
            throws EnvironmentClient.UnreachableException, EnvironmentClient.NoAnswerException {
        RouteRepository.Row route = routes.find(request.definitionId()).orElseThrow();
        JsonNode snapshot = routes.history(route.id(), request.version()).orElseThrow().snapshot();
        String path = "/admin/v1/routes/" + route.maintask() + "/" + route.subtask();
        String probeResult = null;
        if (!"DISABLE".equals(request.action())) {
            ObjectNode probe = Json.object();
            probe.put("maintask", route.maintask());
            probe.set("httpMethod", snapshot.get("http_method"));
            probe.set("pathTemplate", snapshot.get("path_template"));
            probe.putObject("pathVariables");
            probe.put("promotionId", request.id());
            probe.put("actor", actor);
            EnvironmentClient.Response probed = client.send(target, "POST", "/admin/v1/probe", probe);
            if (!probed.ok()) {
                promotions.failed(request.id(), "Yoklama yapılamadı: " + describe(probed));
                return;
            }
            probeResult = Json.stringField(probed.body(), "result");
            promotions.probed(request.id(), probeResult, probed.body());
            if (!"OK".equals(probeResult)) {
                promotions.failed(request.id(), "Yoklama başarısız: " + probeResult + " – "
                        + Json.stringField(probed.body(), "message") + "; hedef ortamda hiçbir şey değişmedi.");
                return;
            }
        }
        EnvironmentClient.Response response;
        if ("DISABLE".equals(request.action())) {
            ObjectNode body = Json.object();
            body.put("promotionId", request.id());
            body.put("actor", actor);
            response = client.send(target, "POST", path + "/disable", body);
        } else {
            response = client.send(target, "PUT", path, routePayload(request, route, snapshot, actor));
        }
        if (!response.ok()) {
            promotions.failed(request.id(), describe(response));
            return;
        }
        long configVersion = response.body().get("configVersion").longValue();
        String status = "DISABLE".equals(request.action()) ? "DISABLED" : "ACTIVE";
        String probe = probeResult;
        transactions.executeWithoutResult(tx -> {
            promotions.applied(request.id(), configVersion);
            routes.upsertDeployment(route.id(), target.code(), request.version(), status, actor, probe);
        });
    }

    private ObjectNode routePayload(PromotionRepository.Row request, RouteRepository.Row route, JsonNode snapshot,
                                    String actor) {
        ObjectNode body = Json.object();
        body.put("promotionId", request.id());
        body.put("definitionId", route.id());
        body.put("version", request.version());
        body.set("httpMethod", snapshot.get("http_method"));
        body.set("pathTemplate", snapshot.get("path_template"));
        ArrayNode permissions = body.putArray("permissions");
        List<String> all = new ArrayList<>(RouteSnapshots.strings(snapshot.get("permissions")));
        all.addAll(RouteSnapshots.strings(snapshot.get("foreign_permissions")));
        all.forEach(permissions::add);
        body.set("anonymous", snapshot.get("anonymous"));
        JsonNode profileId = snapshot.get("response_profile_id");
        if (profileId == null || profileId.isNull()) {
            body.putNull("responseProfile");
        } else {
            body.put("responseProfile", profiles.find(profileId.longValue()).orElseThrow().name());
        }
        body.set("definition", snapshot.get("definition"));
        body.put("actor", actor);
        return body;
    }

    private void applyProfile(PromotionRepository.Row request, Environment target, String actor)
            throws EnvironmentClient.UnreachableException, EnvironmentClient.NoAnswerException {
        ProfileRepository.Row profile = profiles.find(request.profileId()).orElseThrow();
        JsonNode snapshot = profiles.history(profile.id(), request.profileVersion()).orElseThrow().snapshot();
        ObjectNode body = Json.object();
        body.put("promotionId", request.id());
        body.put("profileId", profile.id());
        body.put("version", request.profileVersion());
        body.set("definition", snapshot.get("definition"));
        body.put("actor", actor);
        EnvironmentClient.Response response = client.send(target, "PUT",
                "/admin/v1/response-profiles/" + profile.name(), body);
        if (!response.ok()) {
            promotions.failed(request.id(), describe(response));
            return;
        }
        long configVersion = response.body().get("configVersion").longValue();
        transactions.executeWithoutResult(tx -> {
            promotions.applied(request.id(), configVersion);
            profiles.upsertDeployment(profile.id(), target.code(), request.profileVersion(), actor);
        });
    }

    private void applyPlugin(PromotionRepository.Row request, Environment target, String actor)
            throws EnvironmentClient.UnreachableException, EnvironmentClient.NoAnswerException {
        PluginRepository.Row plugin = plugins.find(request.pluginId()).orElseThrow();
        JsonNode snapshot = plugins.history(plugin.id(), request.pluginVersion()).orElseThrow().snapshot();
        ObjectNode body = Json.object();
        body.put("promotionId", request.id());
        body.put("pluginId", plugin.id());
        body.put("version", request.pluginVersion());
        body.set("definition", snapshot.get("definition"));
        body.put("actor", actor);
        EnvironmentClient.Response response = client.send(target, "PUT", "/admin/v1/plugins/" + plugin.name(), body);
        if (!response.ok()) {
            promotions.failed(request.id(), describe(response));
            return;
        }
        long configVersion = response.body().get("configVersion").longValue();
        transactions.executeWithoutResult(tx -> {
            promotions.applied(request.id(), configVersion);
            plugins.upsertDeployment(plugin.id(), target.code(), request.pluginVersion(), actor);
        });
    }

    private static String describe(EnvironmentClient.Response response) {
        String code = response.code();
        String detail = response.detail();
        return (code == null ? "HTTP " + response.status() : code) + (detail == null ? "" : ": " + detail);
    }
}
