package com.acme.broker.envadmin.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.acme.broker.core.runtime.RuntimeSignals;

/**
 * @param environment            code of this environment (DEV, TEST, PROD); returned in listings
 * @param allowedClientSubjects  certificate subjects (RFC 2253) allowed to call; empty accepts any certificate the
 *                               trust store accepts
 */
@ConfigurationProperties("broker.env-admin")
public record EnvAdminProperties(String environment, Signals signals, Upstream upstream, Test test,
                                 List<String> allowedClientSubjects) {

    public EnvAdminProperties {
        environment = environment == null ? "DEV" : environment;
        signals = signals == null ? new Signals(null, null) : signals;
        upstream = upstream == null ? new Upstream(null, null, null, null) : upstream;
        test = test == null ? new Test(false, null, null, null) : test;
        allowedClientSubjects = allowedClientSubjects == null ? List.of() : List.copyOf(allowedClientSubjects);
    }

    public record Signals(String routesChangedChannel, String loadedKeyPrefix) {
        public Signals {
            routesChangedChannel = routesChangedChannel == null ? RuntimeSignals.ROUTES_CHANGED_CHANNEL : routesChangedChannel;
            loadedKeyPrefix = loadedKeyPrefix == null ? RuntimeSignals.LOADED_KEY_PREFIX : loadedKeyPrefix;
        }
    }

    /**
     * Calls to the environment's services (spec and OPTIONS probe).
     *
     * @param headers static headers the services expect from internal callers
     */
    public record Upstream(Duration connectTimeout, Duration requestTimeout, Integer maxSpecBytes,
                           Map<String, String> headers) {
        public Upstream {
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
            requestTimeout = requestTimeout == null ? Duration.ofSeconds(10) : requestTimeout;
            maxSpecBytes = maxSpecBytes == null ? 10 * 1024 * 1024 : maxSpecBytes;
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    /**
     * Test calls, forwarded to the broker's internal endpoint. Enabled only in environments that allow test calls
     * (DEV, TEST); in PROD a POST would create real records.
     */
    public record Test(boolean enabled, String brokerUrl, String secret, Duration timeout) {
        public Test {
            timeout = timeout == null ? Duration.ofSeconds(65) : timeout;
        }
    }
}
