package com.acme.broker.runtime.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.acme.broker.core.naming.Names;
import com.acme.broker.core.runtime.RuntimeSignals;
import com.acme.broker.core.session.SessionRecordCodec;

/**
 * @param environment     DEV, TEST or PROD; written to every log event
 * @param instanceId      this instance's name in {@code broker:loaded:*}; the pod name ({@code HOSTNAME}) by default
 * @param readableHeaders frontend headers that {@code ${header.*}} may read
 * @param logSink         {@code kafka}, or {@code console} for local development
 * @param localOverrides  {@code broker-local.json}, read only in the {@code local} profile
 */
@ConfigurationProperties("broker")
public record BrokerProperties(String environment, String instanceId, List<String> readableHeaders, String logSink,
                               String localOverrides, Cache cache, Session session, Applications applications,
                               Compat compat, Kafka kafka, Spool spool, Forward forward, LegacyCounter legacyCounter,
                               Test test, Local local) {

    public BrokerProperties {
        environment = environment == null ? "DEV" : environment;
        readableHeaders = readableHeaders == null ? List.of("Accept-Language", "X-Clientip", "X-User-Agent")
                : List.copyOf(readableHeaders);
        logSink = logSink == null ? "kafka" : logSink;
        cache = cache == null ? new Cache(null, null, null) : cache;
        session = session == null ? new Session(null, null, null, null, null, null, null, null) : session;
        applications = applications == null ? new Applications(null, null, null, null) : applications;
        compat = compat == null ? new Compat(null) : compat;
        kafka = kafka == null ? new Kafka(null, null, null, null) : kafka;
        spool = spool == null ? new Spool(null, null) : spool;
        forward = forward == null ? new Forward(null, null, null, null, null) : forward;
        legacyCounter = legacyCounter == null ? new LegacyCounter(null, null, null, null, null) : legacyCounter;
        test = test == null ? new Test(false, null) : test;
        local = local == null ? new Local(false) : local;
    }

    public boolean consoleLog() {
        return "console".equalsIgnoreCase(logSink);
    }

    /** Route cache synchronisation. */
    public record Cache(Duration verifyInterval, String routesChangedChannel, String loadedKeyPrefix) {
        public Cache {
            verifyInterval = verifyInterval == null ? Duration.ofSeconds(15) : verifyInterval;
            routesChangedChannel = routesChangedChannel == null ? RuntimeSignals.ROUTES_CHANGED_CHANNEL : routesChangedChannel;
            loadedKeyPrefix = loadedKeyPrefix == null ? RuntimeSignals.LOADED_KEY_PREFIX : loadedKeyPrefix;
        }
    }

    /**
     * The session record the login service writes ({@code user:session:{uid}}, see {@link SessionRecordCodec}) and the
     * requests that are session operations.
     *
     * @param keyPattern      Redis key with {@code {uid}}; the uid comes from the request body
     * @param publicMaintasks maintasks resolved as ordinary (anonymous) routes before the login/logout check, as the
     *                        legacy broker did
     * @param routes          {@code X-Token} value of a session operation → {@code maintask/subtask} of the login
     *                        service's anonymous route that serves it
     */
    public record Session(String keyPattern, String uidField, String tokenField, String clientIpField,
                          String userAgentField, String permissionsField, List<String> publicMaintasks,
                          Map<String, String> routes) {
        public Session {
            publicMaintasks = publicMaintasks == null ? List.of("refresh_permissions", "giris_public")
                    : List.copyOf(publicMaintasks);
            routes = routes == null ? Map.of("login", "giris/login", "logout", "giris/logout") : Map.copyOf(routes);
        }

        public SessionRecordCodec codec() {
            return new SessionRecordCodec(keyPattern, uidField, tokenField, clientIpField, userAgentField,
                    permissionsField);
        }

        public boolean isOperation(String token) {
            return token != null && routes.containsKey(token);
        }

        public boolean isPublic(String maintask) {
            return maintask != null && publicMaintasks.stream().anyMatch(name -> Names.key(name).equals(Names.key(maintask)));
        }
    }

    /**
     * Applications ({@code application} of the body) allowed to use the broker.
     *
     * @param mode            {@code redis}: the JSON array in {@code key}, written by the login service, read at
     *                        startup and every {@code refreshInterval}; {@code static}: {@code list}; {@code off}: no check
     */
    public record Applications(String mode, String key, Duration refreshInterval, List<String> list) {
        public Applications {
            mode = mode == null ? "redis" : mode.toLowerCase(java.util.Locale.ROOT);
            key = key == null ? "broker:applications" : key;
            refreshInterval = refreshInterval == null ? Duration.ofSeconds(30) : refreshInterval;
            list = list == null ? List.of() : List.copyOf(list);
            if (!Set.of("redis", "static", "off").contains(mode)) {
                throw new IllegalArgumentException("broker.applications.mode redis, static ya da off olmalı: " + mode);
            }
        }
    }

    /**
     * @param legacyResponses the legacy broker's HTTP 200 bodies for a refused application or session; false gives
     *                        the common error format with real statuses (400, 401)
     */
    public record Compat(Boolean legacyResponses) {
        public Compat {
            legacyResponses = legacyResponses == null ? Boolean.TRUE : legacyResponses;
        }
    }

    /**
     * @param ackTimeout    how long a request waits for one event's acknowledgement; a little above
     *                      {@code delivery.timeout.ms}
     * @param warmupTimeout how long startup waits for the topic's metadata before giving up
     */
    public record Kafka(String topic, Duration ackTimeout, Boolean warmup, Duration warmupTimeout) {
        public Kafka {
            topic = topic == null ? "broker.logs.v1" : topic;
            ackTimeout = ackTimeout == null ? Duration.ofMillis(2_500) : ackTimeout;
            warmup = warmup == null ? Boolean.TRUE : warmup;
            warmupTimeout = warmupTimeout == null ? Duration.ofSeconds(60) : warmupTimeout;
        }
    }

    /** Events of requests that got 500 because Kafka did not acknowledge; resent in the background. */
    public record Spool(Path directory, Duration resendInterval) {
        public Spool {
            directory = directory == null ? Path.of("/var/lib/broker/log-spool") : directory;
            resendInterval = resendInterval == null ? Duration.ofSeconds(10) : resendInterval;
        }
    }

    /**
     * @param userContextSecret HMAC key of the signed {@code X-User-Context} header; the header is left out when empty
     */
    public record Forward(Duration connectTimeout, Integer maxRequestBytes, Integer maxResponseBytes,
                          String userContextSecret, String userContextHeader) {
        public Forward {
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
            maxRequestBytes = maxRequestBytes == null ? 20 * 1024 * 1024 : maxRequestBytes;
            maxResponseBytes = maxResponseBytes == null ? 20 * 1024 * 1024 : maxResponseBytes;
            userContextHeader = userContextHeader == null ? "X-User-Context" : userContextHeader;
        }
    }

    /**
     * Today's {@code broker.gelen.istekler} counter, as the legacy controller counts it: tags {@code maintask} and
     * {@code subtask}, "Bilinmeyen Proje" / "Bilinmeyen Endpoint" when the body lacks them.
     */
    public record LegacyCounter(String name, String projectTag, String endpointTag, String unknownProject,
                                String unknownEndpoint) {
        public LegacyCounter {
            name = name == null ? "broker.gelen.istekler" : name;
            projectTag = projectTag == null ? "maintask" : projectTag;
            endpointTag = endpointTag == null ? "subtask" : endpointTag;
            unknownProject = unknownProject == null ? "Bilinmeyen Proje" : unknownProject;
            unknownEndpoint = unknownEndpoint == null ? "Bilinmeyen Endpoint" : unknownEndpoint;
        }
    }

    /** The internal test endpoint the environment Admin API calls; enabled only in DEV and TEST. */
    public record Test(boolean enabled, String secret) {
    }

    /**
     * @param skipPermissionCheckForOverrides lets a developer call an override route with a permission nobody has yet
     */
    public record Local(boolean skipPermissionCheckForOverrides) {
    }
}
