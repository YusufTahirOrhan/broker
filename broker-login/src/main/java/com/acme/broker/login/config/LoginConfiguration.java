package com.acme.broker.login.config;

import java.util.Arrays;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import com.acme.broker.login.ldap.BindLdapAuthenticator;
import com.acme.broker.login.ldap.LdapAuthenticator;
import com.acme.broker.login.ldap.StaticLdapAuthenticator;

@Configuration(proxyBeanMethods = false)
public class LoginConfiguration {

    @Bean
    LdapAuthenticator ldapAuthenticator(LoginProperties properties, Environment environment) {
        return ldapAuthenticator(properties.ldap(), environment.getActiveProfiles());
    }

    /** {@code static} users exist only on a developer's machine: in any other profile the service does not start. */
    static LdapAuthenticator ldapAuthenticator(LoginProperties.Ldap ldap, String... activeProfiles) {
        return switch (ldap.mode()) {
            case "bind" -> new BindLdapAuthenticator(ldap);
            case "static" -> {
                if (!Arrays.asList(activeProfiles).contains("local")) {
                    throw new IllegalStateException(
                            "login.ldap.mode=static yalnızca local profilinde kullanılabilir; LDAP adresi verilmeli");
                }
                yield new StaticLdapAuthenticator(ldap.users());
            }
            default -> throw new IllegalStateException("login.ldap.mode bind ya da static olmalı: " + ldap.mode());
        };
    }
}
