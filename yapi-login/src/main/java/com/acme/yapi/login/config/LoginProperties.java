package com.acme.yapi.login.config;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.acme.yapi.core.session.SessionRecordCodec;

/**
 * @param koordinatorSecret  shared secret of the Koordinatör's forced logout ({@code X-Koordinator-Secret}); the
 *                           endpoint refuses every call while it is empty
 * @param userContextSecret  the broker's {@code X-User-Context} key; when set, unsigned calls to login and logout
 *                           get 403
 * @param userContextMaxAge  how far the signature's {@code issuedAt} may be from now
 */
@ConfigurationProperties("login")
public record LoginProperties(Ldap ldap, Session session, Logout logout, String koordinatorSecret,
                              String userContextSecret, Duration userContextMaxAge, Applications applications) {

    public LoginProperties {
        ldap = ldap == null ? new Ldap(null, null, null, null, null, null, null) : ldap;
        session = session == null ? new Session(null, null, null, null, null, null, null) : session;
        logout = logout == null ? new Logout(null) : logout;
        userContextMaxAge = userContextMaxAge == null ? Duration.ofSeconds(60) : userContextMaxAge;
        applications = applications == null ? new Applications(null, null) : applications;
    }

    /**
     * @param mode             {@code bind}: a direct bind with the user's password, as the legacy {@code Ldap}
     *                         classes do (PREPROD, PROD); {@code magic-key}: any user with {@code magicKey} as the
     *                         password, only in the {@code dev} profile, as the legacy DEV did; {@code static}: the
     *                         {@code users} map, only in the {@code local} profile
     * @param principalPattern the bind principal with {@code {uid}}, e.g. {@code {uid}@test.com.tr} (Active Directory
     *                         UPN) or a DN such as {@code uid={uid},ou=people,dc=test}
     */
    public record Ldap(String mode, List<String> urls, String principalPattern, Duration connectTimeout,
                       Duration readTimeout, Map<String, String> users, String magicKey) {
        public Ldap {
            mode = mode == null ? "bind" : mode.toLowerCase(Locale.ROOT);
            urls = urls == null ? List.of() : List.copyOf(urls);
            principalPattern = principalPattern == null ? "{uid}" : principalPattern;
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
            readTimeout = readTimeout == null ? Duration.ofSeconds(5) : readTimeout;
            users = users == null ? Map.of() : Map.copyOf(users);
        }

        public String principal(String uid) {
            return principalPattern.replace("{uid}", uid);
        }
    }

    /** The session record, the same the broker reads (see {@link SessionRecordCodec}). */
    public record Session(String keyPattern, String uidField, String tokenField, String clientIpField,
                          String userAgentField, String permissionsField, Duration ttl) {
        public Session {
            ttl = ttl == null ? Duration.ofSeconds(86_400) : ttl;
        }

        public SessionRecordCodec codec() {
            return new SessionRecordCodec(keyPattern, uidField, tokenField, clientIpField, userAgentField,
                    permissionsField);
        }
    }

    /** @param requireClientMatch a logout closes the session only from the client that opened it */
    public record Logout(Boolean requireClientMatch) {
        public Logout {
            requireClientMatch = requireClientMatch == null ? Boolean.TRUE : requireClientMatch;
        }
    }

    /** The broker's application list ({@code yapi:applications}), copied from the Koordinatör's projects. */
    public record Applications(String key, Duration refreshInterval) {
        public Applications {
            key = key == null ? "yapi:applications" : key;
            refreshInterval = refreshInterval == null ? Duration.ofMinutes(5) : refreshInterval;
        }
    }
}
