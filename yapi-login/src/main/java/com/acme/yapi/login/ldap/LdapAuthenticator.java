package com.acme.yapi.login.ldap;

/** Checks a user's password. An empty uid or password is never valid, so it cannot fall back to an anonymous bind. */
public interface LdapAuthenticator {

    boolean authenticate(String uid, String password);
}
