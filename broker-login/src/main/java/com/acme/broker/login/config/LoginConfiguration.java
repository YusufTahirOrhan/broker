package com.acme.broker.login.config;

import java.util.Arrays;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import com.acme.broker.login.koordinator.HttpKoordinatorClient;
import com.acme.broker.login.koordinator.KoordinatorClient;
import com.acme.broker.login.koordinator.StaticKoordinatorClient;
import com.acme.broker.login.ldap.BindLdapAuthenticator;
import com.acme.broker.login.ldap.LdapAuthenticator;
import com.acme.broker.login.ldap.StaticLdapAuthenticator;

@Configuration(proxyBeanMethods = false)
public class LoginConfiguration {

    @Bean
    LdapAuthenticator ldapAuthenticator(LoginProperties properties, Environment environment) {
        return ldapAuthenticator(properties.ldap(), environment.getActiveProfiles());
    }

    @Bean
    KoordinatorClient koordinatorClient(KoordinatorProperties properties, Environment environment) {
        return koordinatorClient(properties, environment.getActiveProfiles());
    }

    /** Fixed permissions exist only on a developer's machine, like static users. */
    static KoordinatorClient koordinatorClient(KoordinatorProperties koordinator, String... activeProfiles) {
        return switch (koordinator.mode()) {
            case "http" -> new HttpKoordinatorClient(koordinator);
            case "static" -> {
                requireLocal("koordinator.mode=static", activeProfiles);
                yield new StaticKoordinatorClient(koordinator.staticPermissions(), koordinator.staticProjects());
            }
            default -> throw new IllegalStateException("koordinator.mode http ya da static olmalı: " + koordinator.mode());
        };
    }

    private static void requireLocal(String setting, String... activeProfiles) {
        if (!Arrays.asList(activeProfiles).contains("local")) {
            throw new IllegalStateException(setting + " yalnızca local profilinde kullanılabilir");
        }
    }

    /** {@code static} users exist only on a developer's machine: in any other profile the service does not start. */
    static LdapAuthenticator ldapAuthenticator(LoginProperties.Ldap ldap, String... activeProfiles) {
        return switch (ldap.mode()) {
            case "bind" -> new BindLdapAuthenticator(ldap);
            case "static" -> {
                requireLocal("login.ldap.mode=static", activeProfiles);
                yield new StaticLdapAuthenticator(ldap.users());
            }
            default -> throw new IllegalStateException("login.ldap.mode bind ya da static olmalı: " + ldap.mode());
        };
    }
}
