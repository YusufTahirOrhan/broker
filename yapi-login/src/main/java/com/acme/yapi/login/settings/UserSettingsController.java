package com.acme.yapi.login.settings;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.acme.yapi.core.json.Json;
import com.acme.yapi.login.config.LoginProperties;
import com.acme.yapi.login.web.UserContextFilter;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A user's screen settings, where the legacy broker kept them ({@code user:layout:{uid}},
 * {@code user:quick_link:{uid}}): the user is the one the broker signed ({@code X-User-Context}), never a field of the
 * body, so the routes are defined with a session. {@code PUT} stores the body's JSON as compact text without expiry,
 * the text the legacy {@code RedisTemplate} wrote, and answers it; {@code GET} answers the stored text as it is, or
 * {@code null} when there is none.
 */
@RestController
public class UserSettingsController {

    private static final Logger log = LoggerFactory.getLogger(UserSettingsController.class);
    private static final String PATH = "/users/me/settings/{name}";

    private final StringRedisTemplate redis;
    private final LoginProperties.UserSettings settings;

    public UserSettingsController(StringRedisTemplate redis, LoginProperties properties) {
        this.redis = redis;
        this.settings = properties.userSettings();
    }

    @GetMapping(path = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> get(@PathVariable String name,
                                      @RequestAttribute(name = UserContextFilter.USER, required = false) String uid) {
        String pattern = settings.keys().get(name);
        if (pattern == null) {
            return refused(HttpStatus.NOT_FOUND, "Bilinmeyen ayar: " + name);
        }
        if (uid == null || uid.isBlank()) {
            return refused(HttpStatus.FORBIDDEN, "Çağrı bir kullanıcıya ait değil; route oturumlu olmalı.");
        }
        String value;
        try {
            value = redis.opsForValue().get(pattern.replace("{uid}", uid));
        } catch (RuntimeException e) {
            log.error("Kullanıcı ayarı okunamadı: uid={}, ayar={}", uid, name, e);
            return refused(HttpStatus.SERVICE_UNAVAILABLE, "Ayar okunamadı.");
        }
        return json(value == null ? "null" : value);
    }

    @PutMapping(path = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> put(@PathVariable String name,
                                      @RequestAttribute(name = UserContextFilter.USER, required = false) String uid,
                                      @RequestBody(required = false) byte[] raw) {
        String pattern = settings.keys().get(name);
        if (pattern == null) {
            return refused(HttpStatus.NOT_FOUND, "Bilinmeyen ayar: " + name);
        }
        if (uid == null || uid.isBlank()) {
            return refused(HttpStatus.FORBIDDEN, "Çağrı bir kullanıcıya ait değil; route oturumlu olmalı.");
        }
        JsonNode body = raw == null ? null : Json.tryParse(raw);
        if (body == null || body.isMissingNode()) {
            return refused(HttpStatus.BAD_REQUEST, "Gövde JSON değil.");
        }
        String text = Json.write(body);
        try {
            redis.opsForValue().set(pattern.replace("{uid}", uid), text);
        } catch (RuntimeException e) {
            log.error("Kullanıcı ayarı yazılamadı: uid={}, ayar={}", uid, name, e);
            return refused(HttpStatus.SERVICE_UNAVAILABLE, "Ayar kaydedilemedi.");
        }
        return json(text);
    }

    private static ResponseEntity<byte[]> json(String text) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(text.getBytes(StandardCharsets.UTF_8));
    }

    private static ResponseEntity<byte[]> refused(HttpStatus status, String message) {
        ObjectNode body = Json.object();
        body.put("response", false);
        body.put("status", status.value());
        body.put("message", message);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(Json.writeBytes(body));
    }
}
