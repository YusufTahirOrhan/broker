package com.acme.broker.login.session;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.acme.broker.core.json.Json;
import com.acme.broker.login.config.LoginProperties;
import com.acme.broker.login.koordinator.KoordinatorClient;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Copies the Koordinatör's project codes into the broker's application list ({@code broker:applications}, a JSON
 * array without expiry) at startup and every {@code login.applications.refresh-interval}. An empty or failed answer
 * leaves the old list in place; brokers keep their last list too.
 */
@Component
public class ApplicationSync {

    private static final Logger log = LoggerFactory.getLogger(ApplicationSync.class);

    private final KoordinatorClient koordinator;
    private final StringRedisTemplate redis;
    private final String key;
    private final Counter failures;

    public ApplicationSync(KoordinatorClient koordinator, StringRedisTemplate redis, LoginProperties properties,
                           MeterRegistry meters) {
        this.koordinator = koordinator;
        this.redis = redis;
        this.key = properties.applications().key();
        this.failures = Counter.builder("login.applications.sync.failed")
                .description("Application listesinin Koordinatör'den yenilenemediği denemeler")
                .register(meters);
    }

    @Scheduled(initialDelay = 0, fixedDelayString = "${login.applications.refresh-interval:5m}")
    public void sync() {
        try {
            List<String> codes = koordinator.projectCodes();
            if (codes.isEmpty()) {
                failures.increment();
                log.warn("Koordinatör boş proje listesi döndü; {} değişmedi", key);
                return;
            }
            redis.opsForValue().set(key, Json.write(codes));
        } catch (Exception e) {
            failures.increment();
            log.error("Application listesi yenilenemedi; {} değişmedi ({})", key, e.toString());
        }
    }
}
