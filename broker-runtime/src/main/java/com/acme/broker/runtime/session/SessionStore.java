package com.acme.broker.runtime.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.acme.broker.core.json.Json;
import com.acme.broker.runtime.config.BrokerProperties;

import tools.jackson.databind.JsonNode;

/**
 * Reads the record the login wrote for a token: one Redis GET per request. The key pattern and field names are
 * settings because the real format must be confirmed from today's broker.
 */
@Component
public class SessionStore {

    private final StringRedisTemplate redis;
    private final BrokerProperties.Session settings;

    public SessionStore(StringRedisTemplate redis, BrokerProperties properties) {
        this.redis = redis;
        this.settings = properties.session();
    }

    public boolean isSessionOperation(String token) {
        return token != null && settings.operations().contains(token);
    }

    /** Empty when there is no session for the token or its record cannot be read; Redis errors propagate. */
    public Optional<Session> find(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String value = redis.opsForValue().get(settings.key(token));
        JsonNode record = Json.tryParse(value);
        if (record == null || !record.isObject()) {
            return Optional.empty();
        }
        String userId = Json.text(record.get(settings.userIdField()));
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        List<String> permissions = new ArrayList<>();
        JsonNode list = record.get(settings.permissionsField());
        if (list != null && list.isArray()) {
            for (JsonNode permission : list) {
                String code = Json.text(permission);
                if (code != null) {
                    permissions.add(code);
                }
            }
        }
        return Optional.of(Session.of(userId, permissions));
    }
}
