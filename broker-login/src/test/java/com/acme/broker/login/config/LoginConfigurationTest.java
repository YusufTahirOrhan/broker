package com.acme.broker.login.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

class LoginConfigurationTest {

    @Test
    void staticUsersStartOnlyInTheLocalProfile() {
        LoginProperties.Ldap fixed = new LoginProperties.Ldap("static", null, null, null, null, Map.of("dev", "dev"));

        assertThatThrownBy(() -> LoginConfiguration.ldapAuthenticator(fixed, "dev"))
                .hasMessageContaining("yalnızca local");
        assertThatThrownBy(() -> LoginConfiguration.ldapAuthenticator(fixed)).hasMessageContaining("yalnızca local");
        assertThat(LoginConfiguration.ldapAuthenticator(fixed, "local").authenticate("dev", "dev")).isTrue();
        assertThat(LoginConfiguration.ldapAuthenticator(fixed, "local").authenticate("dev", "")).isFalse();
        assertThat(LoginConfiguration.ldapAuthenticator(fixed, "local").authenticate("dev", "x")).isFalse();
    }

    @Test
    void aBindNeedsTheDirectorysAddress() {
        assertThatThrownBy(() -> LoginConfiguration.ldapAuthenticator(
                new LoginProperties.Ldap("bind", null, null, null, null, null))).hasMessageContaining("login.ldap.urls");
        assertThatThrownBy(() -> LoginConfiguration.ldapAuthenticator(
                new LoginProperties.Ldap("kerberos", null, null, null, null, null))).hasMessageContaining("bind ya da static");
    }
}
