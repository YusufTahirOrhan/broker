package com.acme.yapi.login.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/** The settings environments give through {@code YAPI_LOGIN_*} variables. */
class SettingsFileTest {

    private static StandardEnvironment settings(Map<String, Object> variables) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("variables", variables));
        for (PropertySource<?> source : new YamlPropertySourceLoader().load("application.yml",
                new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        return environment;
    }

    @Test
    void theBindPrincipalComesFromItsVariable() throws IOException {
        assertThat(settings(Map.of()).getProperty("login.ldap.principal-pattern")).isEqualTo("{uid}@test.com.tr");
        assertThat(settings(Map.of("YAPI_LOGIN_LDAP_PRINCIPAL_PATTERN", "{uid}@sirket.com.tr"))
                .getProperty("login.ldap.principal-pattern")).isEqualTo("{uid}@sirket.com.tr");
        assertThat(settings(Map.of("YAPI_LOGIN_LDAP_MODE", "bind", "YAPI_LOGIN_LDAP_URLS", "ldaps://dc1:636"))
                .getProperty("login.ldap.urls")).isEqualTo("ldaps://dc1:636");
    }
}
