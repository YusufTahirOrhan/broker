package com.acme.broker.runtime.session;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.acme.broker.core.json.Json;
import com.acme.broker.core.naming.Names;
import com.acme.broker.runtime.config.BrokerProperties;
import com.acme.broker.runtime.metrics.BrokerMetrics;

import tools.jackson.databind.JsonNode;

/**
 * The applications allowed to call the broker ({@code application} in the body), compared without case. In
 * {@code redis} mode the list is the JSON array the login service keeps in {@code broker:applications}; it is read at
 * startup and every {@code refresh-interval}, and the last list that could be read stays in use. Until a list was
 * read the instance does not report ready ({@link ApplicationsHealthIndicator}).
 */
@Component
public class ApplicationList implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ApplicationList.class);

    private final StringRedisTemplate redis;
    private final BrokerProperties.Applications settings;
    private final BrokerMetrics metrics;
    private volatile Set<String> allowed;

    public ApplicationList(StringRedisTemplate redis, BrokerProperties properties, BrokerMetrics metrics) {
        this.redis = redis;
        this.settings = properties.applications();
        this.metrics = metrics;
        this.allowed = "static".equals(settings.mode()) ? keys(settings.list()) : null;
    }

    public boolean checks() {
        return !"off".equals(settings.mode());
    }

    /** Whether a list is in use: always in {@code static} and {@code off} mode, after the first read in {@code redis}. */
    public boolean loaded() {
        return !checks() || allowed != null;
    }

    public boolean allows(String application) {
        if (!checks()) {
            return true;
        }
        Set<String> current = allowed;
        return application != null && current != null && current.contains(Names.key(application));
    }

    @Override
    public void run(ApplicationArguments args) {
        refresh();
    }

    @Scheduled(fixedDelayString = "${broker.applications.refresh-interval:30s}",
            initialDelayString = "${broker.applications.refresh-interval:30s}")
    public void refresh() {
        if (!"redis".equals(settings.mode())) {
            return;
        }
        try {
            JsonNode list = Json.tryParse(redis.opsForValue().get(settings.key()));
            if (list == null || !list.isArray()) {
                failed("anahtar yok ya da JSON dizisi değil");
                return;
            }
            Set<String> names = keys(list.valueStream().map(Json::text).filter(name -> name != null).toList());
            if (names.isEmpty()) {
                failed("liste boş");
                return;
            }
            allowed = names;
        } catch (RuntimeException e) {
            failed(e.toString());
        }
    }

    private void failed(String reason) {
        metrics.applicationsRefreshFailed();
        if (allowed == null) {
            log.error("Application listesi okunamadı, broker hazır değil ({}: {})", settings.key(), reason);
        } else {
            log.warn("Application listesi yenilenemedi, son liste kullanılıyor ({}: {})", settings.key(), reason);
        }
    }

    private static Set<String> keys(Collection<String> names) {
        return names.stream().map(Names::key).collect(Collectors.toUnmodifiableSet());
    }
}
