package com.acme.yapi.runtime.support;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.transaction.support.TransactionTemplate;

import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.route.AlarmRoute;
import com.acme.yapi.core.testing.OracleDatabase;
import com.acme.yapi.envadmin.runtime.ApplyResult;
import com.acme.yapi.envadmin.runtime.RuntimeStore;
import com.acme.yapi.envadmin.testing.RuntimeContainers;

/**
 * The runtime DB as an environment sees it: the schema of yapi-env-admin's migration, written by its
 * {@link RuntimeStore}, the same code that applies promotions.
 */
public final class RuntimeFixture {

    private static final AtomicLong PROMOTIONS = new AtomicLong(1_000);
    private static final DataSource DATA_SOURCE;
    private static final RuntimeStore STORE;
    private static final JdbcClient JDBC;
    private static StringRedisTemplate redis;

    static {
        OracleDatabase oracle = RuntimeContainers.ORACLE;
        Flyway.configure()
                .dataSource(oracle.url(), oracle.owner(), OracleDatabase.PASSWORD)
                .defaultSchema(oracle.schema())
                .createSchemas(false)
                .locations("classpath:db/migration/yapi_runtime")
                .load()
                .migrate();
        // the Admin API's user, as in an environment; the broker reads as yapi_runtime_reader
        SingleConnectionDataSource writer = new SingleConnectionDataSource(oracle.url(), RuntimeContainers.WRITER,
                OracleDatabase.PASSWORD, true);
        writer.setAutoCommit(true);
        writer.setSchema(oracle.schema());
        DATA_SOURCE = writer;
        JDBC = JdbcClient.create(DATA_SOURCE);
        STORE = new RuntimeStore(JDBC, new TransactionTemplate(new DataSourceTransactionManager(DATA_SOURCE)));
    }

    private RuntimeFixture() {
    }

    /** The broker's connection: the read-only user of the environment, tables through {@code CURRENT_SCHEMA}. */
    public static void register(DynamicPropertyRegistry registry) {
        RuntimeContainers.register(registry);
        registry.add("spring.datasource.username", () -> RuntimeContainers.READER);
    }

    /** Empties the runtime tables; the config version keeps growing so brokers see the next write as new. */
    public static void reset() {
        RuntimeContainers.reset();
    }

    public static long project(String maintask, String baseUrl) {
        return project(maintask, baseUrl, null);
    }

    /** A project whose subtasks inherit {@code alarmRoute} (null: none). */
    public static long project(String maintask, String baseUrl, AlarmRoute alarmRoute) {
        return project(maintask, baseUrl, alarmRoute, null);
    }

    /** A project with its permission prefix, as the central admin sends it. */
    public static long project(String maintask, String baseUrl, AlarmRoute alarmRoute, String permissionPrefix) {
        STORE.upsertProject(maintask, baseUrl, true, alarmRoute, permissionPrefix);
        return configVersion();
    }

    public static void profile(String name, int version, String definition) {
        applied(STORE.applyProfile(new RuntimeStore.ProfileSnapshot(name, 3, version, Json.parse(definition),
                "test", PROMOTIONS.incrementAndGet())));
    }

    public static long route(String maintask, String subtask, String method, String path, List<String> permissions,
                             boolean anonymous, String profile, String definition) {
        return applied(STORE.applyRoute(new RuntimeStore.RouteSnapshot(maintask, subtask, 17, 3, method, path,
                permissions, anonymous, profile, Json.parse(definition), "test", PROMOTIONS.incrementAndGet())));
    }

    /** A client of the test Redis for code that runs before or outside the Spring context. */
    public static synchronized StringRedisTemplate redis() {
        if (redis == null) {
            LettuceConnectionFactory connections = new LettuceConnectionFactory(RuntimeContainers.redisHost(),
                    RuntimeContainers.redisPort());
            connections.afterPropertiesSet();
            connections.start();
            redis = new StringRedisTemplate(connections);
        }
        return redis;
    }

    public static long configVersion() {
        return STORE.configVersion().version();
    }

    private static long applied(ApplyResult result) {
        if (result instanceof ApplyResult.Applied applied) {
            return applied.configVersion();
        }
        throw new IllegalStateException("Uygulanamadı: " + result);
    }
}
