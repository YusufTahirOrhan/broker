package com.acme.yapi.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** Without {@code login.user-context-secret} nothing checks the broker's signature: only a developer's machine. */
class UserContextSecretTest {

    /** {@code profile} null: the default profile. */
    private static ConfigurableApplicationContext start(String secret, String profile, String... extra) {
        // arguments, not default properties: application.yml's empty defaults would win over those
        String[] arguments = java.util.stream.Stream.concat(java.util.stream.Stream.of("--server.port=0",
                        "--login.user-context-secret=" + secret,
                        "--login.ldap.urls=ldap://localhost:1",
                        "--login.applications.refresh-interval=1h",
                        // no Redis is needed to start; the first application sync fails and is logged
                        "--spring.data.redis.port=1"), java.util.Arrays.stream(extra)).toArray(String[]::new);
        SpringApplicationBuilder builder = new SpringApplicationBuilder(LoginApplication.class);
        return (profile == null ? builder : builder.profiles(profile)).run(arguments);
    }

    @Test
    void outsideTheLocalProfileTheServiceDoesNotStartWithoutTheSecret() {
        assertThatThrownBy(() -> start("", "dev", "--login.ldap.magic-key=dev-anahtar")).rootCause()
                .hasMessageContaining("login.user-context-secret");
        assertThatThrownBy(() -> start("", null)).rootCause().hasMessageContaining("login.user-context-secret");

        try (ConfigurableApplicationContext local = start("", "local")) {
            assertThat(local.isActive()).isTrue();
        }
        // DEV logs in with the magic key; without it the service does not start either
        assertThatThrownBy(() -> start("k3y", "dev")).rootCause().hasMessageContaining("YAPI_LOGIN_MAGIC_KEY");
        try (ConfigurableApplicationContext dev = start("k3y", "dev", "--login.ldap.magic-key=dev-anahtar")) {
            assertThat(dev.isActive()).isTrue();
        }
    }
}
