package com.acme.broker.admin.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param readableHeaders frontend headers {@code ${header.*}} may read; must match the brokers' list
 */
@ConfigurationProperties("broker.admin")
public record AdminProperties(List<String> readableHeaders, Cors cors, Security security, Environments environments,
                              Catalog catalog, Authorization authorization, Drift drift) {

    public AdminProperties {
        readableHeaders = readableHeaders == null ? List.of("Accept-Language", "X-Clientip", "X-User-Agent")
                : List.copyOf(readableHeaders);
        cors = cors == null ? new Cors(null) : cors;
        security = security == null ? new Security(null, null, null, null) : security;
        environments = environments == null ? new Environments(null, null, null, null) : environments;
        catalog = catalog == null ? new Catalog(null, null, null, null) : catalog;
        authorization = authorization == null ? new Authorization(null, null, null) : authorization;
        drift = drift == null ? new Drift(null) : drift;
    }

    public record Cors(List<String> allowedOrigins) {
        public Cors {
            allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        }
    }

    /**
     * Where roles come from in the SSO token. A claim value that is a role name is taken as is; any other value is
     * looked up in {@code groupRoles} (group → roles).
     */
    public record Security(String rolesClaim, String usernameClaim, String displayNameClaim,
                           Map<String, List<String>> groupRoles) {
        public Security {
            rolesClaim = rolesClaim == null ? "roles" : rolesClaim;
            usernameClaim = usernameClaim == null ? "preferred_username" : usernameClaim;
            displayNameClaim = displayNameClaim == null ? "name" : displayNameClaim;
            groupRoles = groupRoles == null ? Map.of() : Map.copyOf(groupRoles);
        }
    }

    /**
     * Calls to the environments' Admin APIs. {@code sslBundle} names the client certificate bundle for mTLS; without
     * it calls go without a client certificate (local development).
     */
    public record Environments(String sslBundle, Duration connectTimeout, Duration timeout, Duration lagWarning) {
        public Environments {
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
            timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
            lagWarning = lagWarning == null ? Duration.ofMinutes(1) : lagWarning;
        }
    }

    /**
     * The project service. {@code mode} is {@code http} or {@code memory}; memory serves {@code projects} and is for
     * local development and tests.
     */
    public record Catalog(String mode, String url, String syncCron, Map<String, String> projects) {
        public Catalog {
            mode = mode == null ? "http" : mode;
            syncCron = syncCron == null ? "0 7 * * * *" : syncCron;
            projects = projects == null ? Map.of() : Map.copyOf(projects);
        }
    }

    /**
     * The authorization service of each environment. {@code mode} is {@code http} (one URL per environment) or
     * {@code memory}.
     */
    public record Authorization(String mode, Map<String, String> urls, Map<String, List<String>> permissions) {
        public Authorization {
            mode = mode == null ? "http" : mode;
            urls = urls == null ? Map.of() : Map.copyOf(urls);
            permissions = permissions == null ? Map.of() : Map.copyOf(permissions);
        }
    }

    /** The nightly check that probes every active route and compares environments with the central records. */
    public record Drift(String cron) {
        public Drift {
            cron = cron == null ? "0 23 2 * * *" : cron;
        }
    }
}
