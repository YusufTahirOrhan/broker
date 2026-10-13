package com.acme.broker.runtime.support;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.transaction.support.TransactionTemplate;

import com.acme.broker.core.json.Json;
import com.acme.broker.envadmin.runtime.ApplyResult;
import com.acme.broker.envadmin.runtime.RuntimeStore;
import com.acme.broker.envadmin.testing.RuntimeContainers;

/**
 * The runtime DB as an environment sees it: the schema of broker-env-admin's migration, written by its
 * {@link RuntimeStore}, the same code that applies promotions.
 */
public final class RuntimeFixture {

    private static final AtomicLong PROMOTIONS = new AtomicLong(1_000);
    private static final DataSource DATA_SOURCE;
    private static final RuntimeStore STORE;
    private static final JdbcClient JDBC;

    static {
        String url = RuntimeContainers.POSTGRES.getJdbcUrl();
        String user = RuntimeContainers.POSTGRES.getUsername();
        String password = RuntimeContainers.POSTGRES.getPassword();
        Flyway.configure()
                .dataSource(url, user, password)
                .schemas("broker_runtime")
                .defaultSchema("broker_runtime")
                .locations("classpath:db/migration/broker_runtime")
                .load()
                .migrate();
        DATA_SOURCE = new DriverManagerDataSource(url, user, password);
        JDBC = JdbcClient.create(DATA_SOURCE);
        STORE = new RuntimeStore(JDBC, new TransactionTemplate(new DataSourceTransactionManager(DATA_SOURCE)));
    }

    private RuntimeFixture() {
    }

    public static void register(DynamicPropertyRegistry registry) {
        RuntimeContainers.register(registry);
    }

    /** Empties the runtime tables; the config version keeps growing so brokers see the next write as new. */
    public static void reset() {
        JDBC.sql("TRUNCATE broker_runtime.runtime_route, broker_runtime.runtime_response_profile, "
                + "broker_runtime.runtime_project").update();
    }

    public static long project(String maintask, String baseUrl) {
        STORE.upsertProject(maintask, baseUrl, true);
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
