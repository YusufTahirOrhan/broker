package com.acme.yapi.admin.config;

import java.time.Duration;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.acme.yapi.admin.security.Role;

/**
 * @param readableHeaders frontend headers {@code ${header.*}} may read; must match the brokers' list
 */
@ConfigurationProperties("yapi.admin")
public record AdminProperties(List<String> readableHeaders, Cors cors, Session session, Environments environments,
                              Catalog catalog, Authorization authorization, Drift drift) {

    public AdminProperties {
        readableHeaders = readableHeaders == null ? List.of("Accept-Language", "X-Clientip", "X-User-Agent")
                : List.copyOf(readableHeaders);
        cors = cors == null ? new Cors(null) : cors;
        session = session == null ? new Session(null, null, null) : session;
        environments = environments == null ? new Environments(null, null, null, null) : environments;
        catalog = catalog == null ? new Catalog(null, null, null, null) : catalog;
        authorization = authorization == null ? new Authorization(null, null) : authorization;
        drift = drift == null ? new Drift(null) : drift;
    }

    public record Cors(List<String> allowedOrigins) {
        public Cors {
            allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        }
    }

    /**
     * Sign-in with the session of the company's login application: the screen sends {@code X-Token}, {@code X-Uid},
     * {@code X-Clientip} and {@code X-User-Agent}, {@code environment}'s Admin API checks the record and its answer is
     * kept for {@code cacheTtl}.
     *
     * @param roles admin role → the Koordinatör permissions of the session that give it (any one is enough)
     */
    public record Session(String environment, Duration cacheTtl, Map<String, List<String>> roles) {
        public Session {
            environment = environment == null ? "PROD" : environment;
            cacheTtl = cacheTtl == null ? Duration.ofSeconds(30) : cacheTtl;
            roles = roles == null || roles.isEmpty() ? Map.of(
                    "VIEWER", List.of("YAPI_DEVELOPER", "YAPI_ADMIN"),
                    "ROUTE_EDITOR", List.of("YAPI_DEVELOPER", "YAPI_ADMIN"),
                    "APPROVER", List.of("YAPI_ADMIN")) : Map.copyOf(roles);
        }

        /** The admin roles the session's permissions give. */
        public Set<Role> rolesOf(Collection<String> permissions) {
            Set<Role> granted = EnumSet.noneOf(Role.class);
            roles.forEach((role, needed) -> {
                if (needed.stream().anyMatch(permissions::contains)) {
                    Role.parse(role).ifPresent(granted::add);
                }
            });
            return granted;
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
     * The project catalog. {@code mode} is {@code http} (the Yapi projects of {@code environment}'s Koordinatör) or
     * {@code memory}; memory serves {@code projects} and is for local development and tests.
     */
    public record Catalog(String mode, String environment, String syncCron, Map<String, String> projects) {
        public Catalog {
            mode = mode == null ? "http" : mode;
            environment = environment == null ? "PROD" : environment;
            syncCron = syncCron == null ? "0 7 * * * *" : syncCron;
            projects = projects == null ? Map.of() : Map.copyOf(projects);
        }
    }

    /**
     * The permissions of each environment. {@code mode} is {@code http} (each environment's Koordinatör) or
     * {@code memory}.
     */
    public record Authorization(String mode, Map<String, List<String>> permissions) {
        public Authorization {
            mode = mode == null ? "http" : mode;
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
