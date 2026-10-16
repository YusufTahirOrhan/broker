package com.acme.broker.login.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.acme.broker.core.json.Json;
import com.acme.broker.login.config.LoginProperties;
import com.acme.broker.login.session.LoginService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code POST /login} and {@code POST /logout} answer HTTP 200 with the legacy bodies whatever happens; the broker
 * passes them to the frontend unchanged. The body is read leniently: a missing or broken body is a refused login.
 */
@RestController
public class LoginController {

    static final String CLIENT_IP = "X-Clientip";
    static final String USER_AGENT = "X-User-Agent";

    private final LoginService logins;
    private final byte[] koordinatorSecret;

    public LoginController(LoginService logins, LoginProperties properties) {
        this.logins = logins;
        String secret = properties.koordinatorSecret();
        this.koordinatorSecret = secret == null || secret.isEmpty() ? null : secret.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping(path = "/login", produces = MediaType.APPLICATION_JSON_VALUE)
    public ObjectNode login(@RequestBody(required = false) byte[] raw,
                            @RequestHeader(value = CLIENT_IP, required = false) String clientIp,
                            @RequestHeader(value = USER_AGENT, required = false) String userAgent) {
        JsonNode body = Json.tryParse(raw);
        return switch (logins.login(text(body, "uid"), text(body, "password"), clientIp, userAgent)) {
            case LoginService.LoginResult.LoggedIn loggedIn -> LegacyBodies.loggedIn(loggedIn.token());
            case LoginService.LoginResult.NotStored ignored -> LegacyBodies.notStored();
            case LoginService.LoginResult.Refused refused -> LegacyBodies.refused(refused.ldap());
        };
    }

    @PostMapping(path = "/logout", produces = MediaType.APPLICATION_JSON_VALUE)
    public ObjectNode logout(@RequestBody(required = false) byte[] raw,
                             @RequestHeader(value = CLIENT_IP, required = false) String clientIp,
                             @RequestHeader(value = USER_AGENT, required = false) String userAgent) {
        return logins.logout(text(Json.tryParse(raw), "uid"), clientIp, userAgent)
                ? LegacyBodies.loggedOut() : LegacyBodies.logoutFailed();
    }

    /**
     * The Koordinatör closes a user's session (legacy {@code expire_user_triggered_from_koordinator}); it calls the
     * service directly, not through the broker. 204 closed, 404 no session, 403 wrong or unset secret.
     */
    @PostMapping("/internal/v1/sessions/{uid}/logout")
    public ResponseEntity<Void> expire(@PathVariable String uid,
                                       @RequestHeader(value = "X-Koordinator-Secret", required = false) String secret) {
        if (koordinatorSecret == null || secret == null
                || !MessageDigest.isEqual(koordinatorSecret, secret.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(403).build();
        }
        return logins.expire(uid) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    private static String text(JsonNode body, String field) {
        return body == null ? null : Json.text(body.get(field));
    }
}
