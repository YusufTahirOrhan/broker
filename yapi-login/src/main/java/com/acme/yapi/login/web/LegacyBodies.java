package com.acme.yapi.login.web;

import com.acme.yapi.core.json.Json;

import tools.jackson.databind.node.ObjectNode;

/**
 * The legacy broker's login and logout answers, key for key; frontends depend on them. {@code LDAP} on success and
 * {@code Ldap} on failure differ as they did, and a login whose record could not be written has no {@code response}.
 */
final class LegacyBodies {

    private LegacyBodies() {
    }

    static ObjectNode loggedIn(String token) {
        ObjectNode body = Json.object();
        body.put("response", true);
        body.put("status", 200);
        body.put("LDAP", true);
        body.put("login", true);
        body.put("X-Token", token);
        return body;
    }

    static ObjectNode notStored() {
        ObjectNode body = Json.object();
        body.put("status", 401);
        body.put("Ldap", false);
        body.put("login", false);
        body.put("X-Token", "404");
        return body;
    }

    static ObjectNode refused(boolean ldap) {
        ObjectNode body = Json.object();
        body.put("response", false);
        body.put("status", 401);
        body.put("Ldap", ldap);
        body.put("login", false);
        return body;
    }

    static ObjectNode loggedOut() {
        ObjectNode body = Json.object();
        body.put("response", true);
        body.put("status", 200);
        body.put("message", "logged out");
        return body;
    }

    static ObjectNode logoutFailed() {
        ObjectNode body = Json.object();
        body.put("response", false);
        body.put("status", 301);
        body.put("message", "something went wrong with logging out.");
        return body;
    }
}
