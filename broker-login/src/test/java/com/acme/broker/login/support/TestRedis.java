package com.acme.broker.login.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;

/** One Redis for the module's tests; each test class uses its own database so their keys never meet. */
public final class TestRedis {

    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        REDIS.start();
    }

    private TestRedis() {
    }

    public static void register(DynamicPropertyRegistry registry, int database) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.database", () -> database);
    }
}
