package com.acme.yapi.runtime.route;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.acme.yapi.core.runtime.RuntimeSignals;
import com.acme.yapi.runtime.config.YapiProperties;
import com.acme.yapi.runtime.config.InstanceId;
import com.acme.yapi.runtime.metrics.YapiMetrics;

/**
 * Routes in memory. The request path only reads the current snapshot; reloads come from the Redis signal and, for
 * missed signals, a version check every 15 seconds. The snapshot reference is swapped atomically.
 *
 * <p>The first load runs as an {@link ApplicationRunner}: Spring Boot does not report the instance ready for traffic
 * until it is done.
 */
@Component
public class RouteCache implements ApplicationRunner, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RouteCache.class);

    private final RouteRepository repository;
    private final List<SnapshotCustomizer> customizers;
    private final StringRedisTemplate redis;
    private final YapiProperties.Cache settings;
    private final String instanceId;
    private final YapiMetrics metrics;

    private volatile RouteSnapshot loaded = RouteSnapshot.EMPTY;
    private volatile RouteSnapshot snapshot = RouteSnapshot.EMPTY;

    public RouteCache(RouteRepository repository, List<SnapshotCustomizer> customizers, StringRedisTemplate redis,
                      YapiProperties properties, InstanceId instanceId, YapiMetrics metrics) {
        this.repository = repository;
        this.customizers = customizers;
        this.redis = redis;
        this.settings = properties.cache();
        this.instanceId = instanceId.value();
        this.metrics = metrics;
        metrics.configVersion(() -> snapshot.version());
    }

    /** Request path: memory only. */
    public Optional<Route> find(String maintask, String subtask) {
        return snapshot.find(maintask, subtask);
    }

    public RouteSnapshot snapshot() {
        return snapshot;
    }

    @Override
    public void run(ApplicationArguments args) {
        reload();
        log.info("Route'lar yüklendi: v{}, {} route", snapshot.version(), snapshot.size());
    }

    @Override
    public int getOrder() {
        return 100;
    }

    /** Redis pub/sub listener. */
    public void onRoutesChanged(long version) {
        if (version > loaded.version()) {
            reload();
        }
    }

    /** Safety net for missed signals; also keeps this instance's {@code yapi:loaded} key alive. */
    @Scheduled(fixedDelayString = "${yapi.cache.verify-interval:15s}", initialDelayString = "${yapi.cache.verify-interval:15s}")
    public void verify() {
        try {
            boolean customizationChanged = customizers.stream().anyMatch(SnapshotCustomizer::changed);
            if (repository.currentVersion() > loaded.version()) {
                reload();
            } else if (customizationChanged) {
                customize(loaded);
            }
            reportLoaded();
        } catch (RuntimeException e) {
            log.warn("Route versiyon kontrolü başarısız; mevcut snapshot (v{}) ile devam ediliyor", snapshot.version(), e);
        }
    }

    public synchronized void reload() {
        RouteSnapshot fresh = repository.loadSnapshot();
        if (fresh.version() > loaded.version()) {
            loaded = fresh;
            customize(fresh);
            log.info("Route snapshot'ı yenilendi: v{}, {} route", snapshot.version(), snapshot.size());
        }
        reportLoaded();
    }

    private synchronized void customize(RouteSnapshot base) {
        RouteSnapshot result = base;
        for (SnapshotCustomizer customizer : customizers) {
            result = customizer.customize(result);
        }
        snapshot = result;
    }

    private void reportLoaded() {
        try {
            redis.opsForValue().set(settings.loadedKeyPrefix() + instanceId,
                    new RuntimeSignals.Loaded(snapshot.version(), Instant.now()).encode(), RuntimeSignals.LOADED_TTL);
        } catch (RuntimeException e) {
            log.warn("Yüklenen versiyon Redis'e yazılamadı", e);
        }
    }
}
