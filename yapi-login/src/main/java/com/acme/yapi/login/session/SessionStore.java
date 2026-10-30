package com.acme.yapi.login.session;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Component;

import com.acme.yapi.core.session.SessionRecord;
import com.acme.yapi.core.session.SessionRecordCodec;
import com.acme.yapi.login.config.LoginProperties;

/**
 * The session records in Redis, as the legacy broker kept them: {@code SET user:session:{uid}} with a one-day expiry
 * at login (a new login overwrites the old session), no extension afterwards. A login again from the session's
 * client rewrites the record in place and keeps its remaining time.
 */
@Component
public class SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);

    private final StringRedisTemplate redis;
    private final LoginProperties.Session settings;
    private final SessionRecordCodec codec;

    public SessionStore(StringRedisTemplate redis, LoginProperties properties) {
        this.redis = redis;
        this.settings = properties.session();
        this.codec = settings.codec();
    }

    /** Writes the record and checks it with {@code EXISTS}; false when either fails. */
    public boolean write(SessionRecord record) {
        String key = codec.key(record.uid());
        try {
            redis.opsForValue().set(key, codec.write(record), settings.ttl());
            return Boolean.TRUE.equals(redis.hasKey(key));
        } catch (RuntimeException e) {
            log.error("Oturum kaydı Redis'e yazılamadı: uid={}", record.uid(), e);
            return false;
        }
    }

    /**
     * Rewrites a record that is still there with {@code SET ... XX KEEPTTL}: the remaining time stays and an expired
     * session is not brought back. False when the record is gone or Redis fails.
     */
    public boolean rewrite(SessionRecord record) {
        byte[] key = redis.getStringSerializer().serialize(codec.key(record.uid()));
        byte[] value = redis.getStringSerializer().serialize(codec.write(record));
        try {
            return Boolean.TRUE.equals(redis.execute((RedisCallback<Boolean>) connection ->
                    connection.stringCommands().set(key, value, Expiration.keepTtl(), SetOption.ifPresent())));
        } catch (RuntimeException e) {
            log.warn("Oturum kaydı Redis'te güncellenemedi: uid={}", record.uid(), e);
            return false;
        }
    }

    /** Redis errors propagate. */
    public Optional<SessionRecord> find(String uid) {
        return codec.read(redis.opsForValue().get(codec.key(uid)));
    }

    /** True when a record was there and is gone; Redis errors propagate. */
    public boolean delete(String uid) {
        return Boolean.TRUE.equals(redis.delete(codec.key(uid)));
    }
}
