package com.acme.yapi.login.ldap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import com.acme.yapi.login.config.LoginProperties;
import com.acme.yapi.login.support.EmbeddedLdap;

class BindLdapAuthenticatorTest {

    static final EmbeddedLdap LDAP = EmbeddedLdap.start().user("ali", "s3cret-Parola");

    @AfterAll
    static void stop() {
        LDAP.stop();
    }

    private static BindLdapAuthenticator authenticator() {
        return new BindLdapAuthenticator(new LoginProperties.Ldap("bind", List.of(LDAP.url()),
                EmbeddedLdap.principalPattern(), null, null, null));
    }

    @Test
    void aBlankPasswordNeverReachesTheDirectory() {
        // Active Directory counts every bind with a wrong password towards the account's lockout
        BindLdapAuthenticator ldap = authenticator();
        int binds = LDAP.binds();

        for (String password : List.of(" ", "   ", "\t", " \n ")) {
            assertThat(ldap.authenticate("ali", password)).as("[%s]", password).isFalse();
        }
        assertThat(LDAP.binds()).isEqualTo(binds);

        assertThat(ldap.authenticate("ali", "yanlis")).isFalse();
        assertThat(ldap.authenticate("ali", "s3cret-Parola")).isTrue();
        assertThat(LDAP.binds()).isEqualTo(binds + 2);
    }
}
