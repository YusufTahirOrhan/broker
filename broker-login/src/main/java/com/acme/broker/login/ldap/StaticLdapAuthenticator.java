package com.acme.broker.login.ldap;

import java.util.Map;
import java.util.Objects;

/** Fixed users and passwords for the {@code local} profile; the application refuses to start with it elsewhere. */
public class StaticLdapAuthenticator implements LdapAuthenticator {

    private final Map<String, String> users;

    public StaticLdapAuthenticator(Map<String, String> users) {
        this.users = Map.copyOf(users);
    }

    @Override
    public boolean authenticate(String uid, String password) {
        if (uid == null || uid.isBlank() || password == null || password.isEmpty()) {
            return false;
        }
        return Objects.equals(users.get(uid), password);
    }
}
