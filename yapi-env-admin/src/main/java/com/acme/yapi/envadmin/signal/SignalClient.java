package com.acme.yapi.envadmin.signal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.acme.yapi.core.runtime.RuntimeSignals;
import com.acme.yapi.envadmin.config.EnvAdminProperties;

/** Redis side of the Admin API: the change signal after a commit and the versions instances report. */
@Component
public class SignalClient {

    private static final Logger log = LoggerFactory.getLogger(SignalClient.class);

    private final StringRedisTemplate redis;
    private final EnvAdminProperties.Signals signals;

    public SignalClient(StringRedisTemplate redis, EnvAdminProperties properties) {
        this.redis = redis;
        this.signals = properties.signals();
    }

    /**
     * Tells brokers to reload. A lost signal is not an error: brokers compare versions every 15 seconds anyway, so the
     * write stays applied and only propagation is slower.
     */
    public boolean publishRoutesChanged(long version) {
        try {
            redis.convertAndSend(signals.routesChangedChannel(), Long.toString(version));
            return true;
        } catch (RuntimeException e) {
            log.warn("Redis sinyali gönderilemedi; broker'lar versiyon kontrolüyle yetişecek (version={})", version, e);
            return false;
        }
    }

    public record Instance(String instanceId, Long loadedVersion, java.time.Instant loadedAt) {
    }

    public List<Instance> instances() {
        List<String> keys = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions().match(signals.loadedKeyPrefix() + "*").count(200).build();
        try (Cursor<String> cursor = redis.scan(options)) {
            cursor.forEachRemaining(keys::add);
        }
        if (keys.isEmpty()) {
            return List.of();
        }
        List<String> values = redis.opsForValue().multiGet(keys);
        List<Instance> instances = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            String id = keys.get(i).substring(signals.loadedKeyPrefix().length());
            String value = values == null ? null : values.get(i);
            RuntimeSignals.Loaded loaded = RuntimeSignals.Loaded.decode(value).orElse(null);
            if (value != null) {
                instances.add(new Instance(id, loaded == null ? null : loaded.version(),
                        loaded == null ? null : loaded.loadedAt()));
            }
        }
        instances.sort(Comparator.comparing(Instance::instanceId));
        return instances;
    }
}
