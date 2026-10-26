package com.acme.yapi.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** Without {@code login.user-context-secret} nothing checks the broker's signature: only a developer's machine. */
class UserContextSecretTest {

    private static ConfigurableApplicationContext start(String secret, String... profiles) {
        // arguments, not default properties: application.yml's empty defaults would win over those
        return new SpringApplicationBuilder(LoginApplication.class)
                .profiles(profiles)
                .run("--server.port=0",
                        "--login.user-context-secret=" + secret,
                        "--login.ldap.urls=ldap://localhost:1",
                        "--login.applications.refresh-interval=1h",
                        // no Redis is needed to start; the first application sync fails and is logged
                        "--spring.data.redis.port=1");
    }

    @Test
    void outsideTheLocalProfileTheServiceDoesNotStartWithoutTheSecret() {
        assertThatThrownBy(() -> start("", "dev")).rootCause().hasMessageContaining("login.user-context-secret");
        assertThatThrownBy(() -> start("")).rootCause().hasMessageContaining("login.user-context-secret");

        try (ConfigurableApplicationContext local = start("", "local")) {
            assertThat(local.isActive()).isTrue();
        }
        try (ConfigurableApplicationContext dev = start("k3y", "dev")) {
            assertThat(dev.isActive()).isTrue();
        }
    }
}
