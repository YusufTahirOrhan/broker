package com.acme.yapi.runtime.session;

import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.acme.yapi.core.session.SessionRecordCodec;
import com.acme.yapi.runtime.config.YapiProperties;

/**
 * Reads the session record the login service wrote for the body's {@code uid} ({@code user:session:{uid}}): one
 * Redis GET per request. The record belongs to the request only when its token and client headers match, as the
 * legacy broker's {@code token_check} decided.
 */
@Component
public class SessionStore {

    private final StringRedisTemplate redis;
    private final YapiProperties.Session settings;
    private final SessionRecordCodec codec;

    public SessionStore(StringRedisTemplate redis, YapiProperties properties) {
        this.redis = redis;
        this.settings = properties.session();
        this.codec = settings.codec();
    }

    public boolean isSessionOperation(String token) {
        return settings.isOperation(token);
    }

    /**
     * The user of a valid session: the record of {@code uid} exists and {@code X-Token}, {@code X-Clientip} and
     * {@code X-User-Agent} equal its values. Empty otherwise; Redis errors propagate.
     */
    public Optional<Session> find(String uid, String token, String clientIp, String userAgent) {
        if (uid == null || uid.isBlank() || token == null) {
            return Optional.empty();
        }
        return codec.read(redis.opsForValue().get(codec.key(uid)))
                .filter(record -> record.matches(token, clientIp, userAgent))
                .map(record -> Session.of(record.uid() == null ? uid : record.uid(), record.permissionList()));
    }
}
