package com.acme.yapi.login.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.acme.yapi.login.koordinator.HttpKoordinatorClient;

/** Fixed users and permissions exist only on a developer's machine. */
class LoginConfigurationTest {

    @Test
    void staticUsersStartOnlyInTheLocalProfile() {
        LoginProperties.Ldap fixed = new LoginProperties.Ldap("static", null, null, null, null, Map.of("dev", "dev"),
                null);

        assertThatThrownBy(() -> LoginConfiguration.ldapAuthenticator(fixed, "dev"))
                .hasMessageContaining("yalnızca local");
        assertThatThrownBy(() -> LoginConfiguration.ldapAuthenticator(fixed)).hasMessageContaining("yalnızca local");
        assertThat(LoginConfiguration.ldapAuthenticator(fixed, "local").authenticate("dev", "dev")).isTrue();
        assertThat(LoginConfiguration.ldapAuthenticator(fixed, "local").authenticate("dev", "")).isFalse();
        assertThat(LoginConfiguration.ldapAuthenticator(fixed, "local").authenticate("dev", "x")).isFalse();
    }

    @Test
    void staticPermissionsStartOnlyInTheLocalProfile() throws Exception {
        KoordinatorProperties fixed = new KoordinatorProperties("static", null, null, null, null, null, null, null,
                null, null, null, List.of("ORDER_READ"), List.of("web"));

        assertThatThrownBy(() -> LoginConfiguration.koordinatorClient(fixed, "dev"))
                .hasMessageContaining("yalnızca local");
        assertThat(LoginConfiguration.koordinatorClient(fixed, "local").permissions("anyone"))
                .contains(List.of("ORDER_READ"));
        assertThat(LoginConfiguration.koordinatorClient(fixed, "local").applications()).containsExactly("web");
        KoordinatorProperties http = new KoordinatorProperties(null, null, null, null, null, null, null, null, null,
                null, null, null, null);
        assertThat(LoginConfiguration.koordinatorClient(http)).isInstanceOf(HttpKoordinatorClient.class);
    }

    @Test
    void aBindNeedsTheDirectorysAddress() {
        assertThatThrownBy(() -> LoginConfiguration.ldapAuthenticator(
                new LoginProperties.Ldap("bind", null, null, null, null, null, null)))
                .hasMessageContaining("login.ldap.urls");
        assertThatThrownBy(() -> LoginConfiguration.ldapAuthenticator(
                new LoginProperties.Ldap("kerberos", null, null, null, null, null, null)))
                .hasMessageContaining("bind, magic-key ya da static");
    }

    @Test
    void theMagicKeyStartsOnlyInTheDevProfileAndOnlyWithAKey() {
        LoginProperties.Ldap magic = new LoginProperties.Ldap("magic-key", null, null, null, null, null, "dev-anahtar");

        for (String[] profiles : new String[][]{{}, {"local"}, {"preprod"}, {"prod"}}) {
            assertThatThrownBy(() -> LoginConfiguration.ldapAuthenticator(magic, profiles))
                    .hasMessageContaining("yalnızca dev");
        }
        assertThat(LoginConfiguration.ldapAuthenticator(magic, "dev").authenticate("12345", "dev-anahtar")).isTrue();
        assertThatThrownBy(() -> LoginConfiguration.ldapAuthenticator(
                new LoginProperties.Ldap("magic-key", null, null, null, null, null, " "), "dev"))
                .hasMessageContaining("YAPI_LOGIN_MAGIC_KEY");
    }
}
