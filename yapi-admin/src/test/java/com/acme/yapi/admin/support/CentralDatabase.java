package com.acme.yapi.admin.support;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;

import com.acme.yapi.core.testing.OracleDatabase;

/**
 * The central DB of the module's tests: one Oracle container for the whole run. The schema belongs to
 * {@code YAPI_ADMIN}; the application connects as {@code yapi_admin_app} with the grants of the migration.
 */
public final class CentralDatabase {

    public static final String APP_USER = "yapi_admin_app";
    public static final OracleDatabase ORACLE = OracleDatabase.of("yapi_admin", APP_USER).start();

    private static final JdbcClient OWNER = JdbcClient.create(
            new SingleConnectionDataSource(ORACLE.url(), ORACLE.owner(), OracleDatabase.PASSWORD, true));

    private static final String[] TABLES = {"promotion_request", "route_deployment", "response_profile_deployment",
            "route_definition_history", "route_permission", "route_definition", "project_environment", "project",
            "response_profile_history", "response_profile"};

    private CentralDatabase() {
    }

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", ORACLE::url);
        registry.add("spring.datasource.username", () -> APP_USER);
        registry.add("spring.datasource.password", () -> OracleDatabase.PASSWORD);
        registry.add("spring.datasource.hikari.schema", ORACLE::schema);
        registry.add("spring.flyway.user", ORACLE::owner);
        registry.add("spring.flyway.password", () -> OracleDatabase.PASSWORD);
        registry.add("spring.flyway.default-schema", ORACLE::schema);
    }

    /** The schema owner: what the application may not do, such as changing environments or emptying tables. */
    public static JdbcClient owner() {
        return OWNER;
    }

    /**
     * Empties every table but {@code environment}. History tables are append-only even for their owner, so their
     * triggers are off while the rows go.
     */
    public static void reset() {
        OWNER.sql("ALTER TRIGGER trg_route_history_append_only DISABLE").update();
        OWNER.sql("ALTER TRIGGER trg_profile_history_append_only DISABLE").update();
        try {
            for (String table : TABLES) {
                OWNER.sql("DELETE FROM " + table).update();
            }
        } finally {
            OWNER.sql("ALTER TRIGGER trg_route_history_append_only ENABLE").update();
            OWNER.sql("ALTER TRIGGER trg_profile_history_append_only ENABLE").update();
        }
    }
}
