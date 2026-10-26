package com.acme.yapi.admin.jobs;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.acme.yapi.admin.environment.Environment;
import com.acme.yapi.admin.environment.EnvironmentClient;
import com.acme.yapi.admin.environment.EnvironmentRepository;
import com.acme.yapi.admin.route.RouteRepository;
import com.acme.yapi.core.json.Json;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Nightly drift check: every route the central records show as ACTIVE is compared with what the environment serves
 * and probed with OPTIONS. PATH_NOT_FOUND and WRONG_METHOD mean the route no longer matches the service; they are
 * logged, counted ({@code yapi.admin.drift}) and kept as the deployment's last probe status.
 */
@Component
public class DriftCheck {

    private static final Logger log = LoggerFactory.getLogger(DriftCheck.class);

    private final EnvironmentRepository environments;
    private final EnvironmentClient client;
    private final RouteRepository routes;
    private final MeterRegistry meters;

    public DriftCheck(EnvironmentRepository environments, EnvironmentClient client, RouteRepository routes,
                      MeterRegistry meters) {
        this.environments = environments;
        this.client = client;
        this.routes = routes;
        this.meters = meters;
    }

    @Scheduled(cron = "${yapi.admin.drift.cron:0 23 2 * * *}")
    public void run() {
        for (Environment environment : environments.all()) {
            try {
                check(environment);
            } catch (RuntimeException e) {
                log.warn("Drift kontrolü {} ortamında yapılamadı: {}", environment.code(), e.getMessage());
                count(environment, "UNREACHABLE");
            }
        }
    }

    private void check(Environment environment) {
        JsonNode listing = client.call(environment, "GET", "/admin/v1/routes", null);
        Map<String, JsonNode> served = new HashMap<>();
        for (JsonNode route : listing.path("routes")) {
            served.put(Json.stringField(route, "maintask") + "/" + Json.stringField(route, "subtask"), route);
        }
        for (RouteRepository.Deployment deployment : routes.activeDeployments(environment.code())) {
            RouteRepository.Row route = routes.find(deployment.definitionId()).orElseThrow();
            String name = route.maintask() + "/" + route.subtask();
            JsonNode runtime = served.get(name);
            if (runtime == null || runtime.get("version").intValue() != deployment.version()
                    || !"ACTIVE".equals(Json.stringField(runtime, "status"))) {
                log.warn("Drift: {} {} merkezde v{} ACTIVE, ortamda {}", environment.code(), name, deployment.version(),
                        runtime == null ? "yok" : "v" + runtime.get("version").intValue() + " " + Json.stringField(runtime, "status"));
                count(environment, "RUNTIME_MISMATCH");
            }
            JsonNode snapshot = routes.history(route.id(), deployment.version()).orElseThrow().snapshot();
            ObjectNode probe = Json.object();
            probe.put("maintask", route.maintask());
            probe.set("httpMethod", snapshot.get("http_method"));
            probe.set("pathTemplate", snapshot.get("path_template"));
            probe.putObject("pathVariables");
            probe.put("actor", "drift-check");
            String result = Json.stringField(client.call(environment, "POST", "/admin/v1/probe", probe), "result");
            routes.probed(route.id(), environment.code(), result);
            if ("PATH_NOT_FOUND".equals(result) || "WRONG_METHOD".equals(result)) {
                log.warn("Drift: {} {} yoklaması {}", environment.code(), name, result);
            }
            count(environment, result);
        }
    }

    private void count(Environment environment, String result) {
        meters.counter("yapi.admin.drift", "environment", environment.code(), "result", String.valueOf(result))
                .increment();
    }
}
