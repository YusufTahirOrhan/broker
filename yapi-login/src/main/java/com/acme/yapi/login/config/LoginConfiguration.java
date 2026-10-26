package com.acme.yapi.login.config;

import java.util.Arrays;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import com.acme.yapi.login.koordinator.HttpKoordinatorClient;
import com.acme.yapi.login.koordinator.KoordinatorClient;
import com.acme.yapi.login.koordinator.StaticKoordinatorClient;
import com.acme.yapi.login.ldap.BindLdapAuthenticator;
import com.acme.yapi.login.ldap.LdapAuthenticator;
import com.acme.yapi.login.ldap.MagicKeyAuthenticator;
import com.acme.yapi.login.ldap.StaticLdapAuthenticator;

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
                requireProfile("local", "koordinator.mode=static", activeProfiles);
                yield new StaticKoordinatorClient(koordinator.staticPermissions(), koordinator.staticProjects());
            }
            default -> throw new IllegalStateException("koordinator.mode http ya da static olmalı: " + koordinator.mode());
        };
    }

    private static void requireProfile(String profile, String setting, String... activeProfiles) {
        if (!Arrays.asList(activeProfiles).contains(profile)) {
            throw new IllegalStateException(setting + " yalnızca " + profile + " profilinde kullanılabilir");
        }
    }

    /**
     * {@code static} users exist only on a developer's machine and the magic key only in DEV: in any other profile the
     * service does not start.
     */
    static LdapAuthenticator ldapAuthenticator(LoginProperties.Ldap ldap, String... activeProfiles) {
        return switch (ldap.mode()) {
            case "bind" -> new BindLdapAuthenticator(ldap);
            case "magic-key" -> {
                requireProfile("dev", "login.ldap.mode=magic-key", activeProfiles);
                yield new MagicKeyAuthenticator(ldap.magicKey());
            }
            case "static" -> {
                requireProfile("local", "login.ldap.mode=static", activeProfiles);
                yield new StaticLdapAuthenticator(ldap.users());
            }
            default -> throw new IllegalStateException("login.ldap.mode bind, magic-key ya da static olmalı: "
                    + ldap.mode());
        };
    }
}
