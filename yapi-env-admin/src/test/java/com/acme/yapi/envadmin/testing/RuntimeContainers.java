package com.acme.yapi.envadmin.testing;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;

import com.acme.yapi.core.testing.OracleDatabase;

/**
 * Oracle and Redis shared by every test class of a module run; started once, stopped by Testcontainers when the JVM
 * ends. The schema belongs to {@code YAPI_RUNTIME}; the Admin API writes as {@code yapi_runtime_writer} and the
 * broker reads as {@code yapi_runtime_reader}, both through the grants of the migration. The broker's tests use the
 * same containers with the runtime schema of this module.
 */
public final class RuntimeContainers {

    public static final String WRITER = "yapi_runtime_writer";
    public static final String READER = "yapi_runtime_reader";

    public static final OracleDatabase ORACLE = OracleDatabase.of("yapi_runtime", WRITER, READER);
    public static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final JdbcClient OWNER;

    static {
        Startables.deepStart(ORACLE.container(), REDIS).join();
        ORACLE.createUsers();
        OWNER = JdbcClient.create(new SingleConnectionDataSource(ORACLE.url(), ORACLE.owner(), OracleDatabase.PASSWORD,
                true));
    }

    private RuntimeContainers() {
    }

    /** The Admin API's connection: Flyway as the schema owner, the application as the writer. */
    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", ORACLE::url);
        registry.add("spring.datasource.username", () -> WRITER);
        registry.add("spring.datasource.password", () -> OracleDatabase.PASSWORD);
        registry.add("spring.datasource.hikari.schema", ORACLE::schema);
        registry.add("spring.flyway.user", ORACLE::owner);
        registry.add("spring.flyway.password", () -> OracleDatabase.PASSWORD);
        registry.add("spring.flyway.default-schema", ORACLE::schema);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    /** The schema owner, for what the applications may not do: emptying tables between tests. */
    public static JdbcClient owner() {
        return OWNER;
    }

    /** Empties the runtime tables; the config version keeps growing so brokers see the next write as new. */
    public static void reset() {
        JdbcClient owner = owner();
        owner.sql("DELETE FROM runtime_route").update();
        owner.sql("DELETE FROM runtime_plugin").update();
        owner.sql("DELETE FROM runtime_response_profile").update();
        owner.sql("DELETE FROM runtime_project").update();
    }

    public static String redisHost() {
        return REDIS.getHost();
    }

    public static int redisPort() {
        return REDIS.getMappedPort(6379);
    }
}
