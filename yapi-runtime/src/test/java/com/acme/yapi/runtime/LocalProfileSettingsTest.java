package com.acme.yapi.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * The local broker reads DEV's sessions; with {@code YAPI_SESSION_KEY_PREFIX} it reads those of a login service
 * running on the developer's machine, which writes {@code local:user:session:{uid}}.
 */
class LocalProfileSettingsTest {

    @Test
    void theLocalBrokerReadsDevSessionsUnlessToldOtherwise() throws IOException {
        assertThat(local(Map.of()).getProperty("yapi.session.key-pattern")).isEqualTo("user:session:{uid}");
        assertThat(local(Map.of("YAPI_SESSION_KEY_PREFIX", "local:")).getProperty("yapi.session.key-pattern"))
                .isEqualTo("local:user:session:{uid}");
    }

    /** The broker's settings in the {@code local} profile, with {@code variables} as the environment. */
    private static StandardEnvironment local(Map<String, Object> variables) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        sources.addFirst(new MapPropertySource("variables", variables));
        YamlPropertySourceLoader yaml = new YamlPropertySourceLoader();
        for (String file : List.of("application-local.yml", "application.yml")) {
            for (PropertySource<?> source : yaml.load(file, new ClassPathResource(file))) {
                sources.addLast(source);
            }
        }
        return environment;
    }
}
