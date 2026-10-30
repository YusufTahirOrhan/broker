package com.acme.yapi.login.web;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

import com.acme.yapi.core.json.Json;
import com.acme.yapi.login.config.LoginProperties;

import tools.jackson.databind.node.ObjectNode;

/**
 * Whether the caller has a session, for frontends deciding to show the login page. The broker resolves the session
 * and signs its user ({@code X-User-Context}) also on anonymous routes, so the route that reaches this endpoint is
 * defined anonymous: a signed user means a valid session, a signature without one means none. The answer is always
 * HTTP 200.
 */
@RestController
public class SessionCheckController {

    private final String loginUrl;

    public SessionCheckController(LoginProperties properties) {
        this.loginUrl = properties.loginUrl() == null || properties.loginUrl().isBlank() ? null : properties.loginUrl();
    }

    @GetMapping(path = "/users/me/session", produces = MediaType.APPLICATION_JSON_VALUE)
    public ObjectNode check(@RequestAttribute(name = UserContextFilter.USER, required = false) String uid) {
        ObjectNode body = Json.object();
        body.put("response", true);
        if (uid != null && !uid.isBlank()) {
            body.put("valid", true);
            body.put("uid", uid);
        } else {
            body.put("valid", false);
            body.put("loginUrl", loginUrl);
        }
        return body;
    }
}
