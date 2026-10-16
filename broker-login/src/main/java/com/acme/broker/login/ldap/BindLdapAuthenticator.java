package com.acme.broker.login.ldap;

import java.util.Map;

import javax.naming.directory.DirContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.ldap.support.LdapUtils;

import com.acme.broker.login.config.LoginProperties;

/**
 * The legacy check: a direct bind as the user ({@code ldapTemplate.getContextSource().getContext(principal,
 * password)}); a successful bind is a valid password, any exception is not. No search.
 */
public class BindLdapAuthenticator implements LdapAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(BindLdapAuthenticator.class);

    private final LdapContextSource contextSource;
    private final LoginProperties.Ldap settings;

    public BindLdapAuthenticator(LoginProperties.Ldap settings) {
        if (settings.urls().isEmpty()) {
            throw new IllegalStateException("login.ldap.urls boş; LDAP adresi verilmeli");
        }
        this.settings = settings;
        this.contextSource = new LdapContextSource();
        contextSource.setUrls(settings.urls().toArray(String[]::new));
        contextSource.setPooled(false);
        contextSource.setBaseEnvironmentProperties(Map.of(
                "com.sun.jndi.ldap.connect.timeout", Long.toString(settings.connectTimeout().toMillis()),
                "com.sun.jndi.ldap.read.timeout", Long.toString(settings.readTimeout().toMillis())));
        contextSource.afterPropertiesSet();
    }

    @Override
    public boolean authenticate(String uid, String password) {
        // as the legacy check: a blank password never reaches the directory (Active Directory would count it as a
        // failed attempt, and an empty one would be an anonymous bind)
        if (uid == null || uid.isBlank() || password == null || password.isBlank()) {
            return false;
        }
        DirContext context = null;
        try {
            context = contextSource.getContext(settings.principal(uid), password);
            return true;
        } catch (Exception e) {
            // the password is never logged, only who and why
            log.info("LDAP doğrulaması başarısız: uid={} ({})", uid, e.getClass().getSimpleName());
            return false;
        } finally {
            LdapUtils.closeContext(context);
        }
    }
}
