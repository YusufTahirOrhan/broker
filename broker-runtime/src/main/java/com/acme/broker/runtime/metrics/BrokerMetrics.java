package com.acme.broker.runtime.metrics;

import java.time.Duration;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.acme.broker.runtime.config.BrokerProperties;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * The broker's meters. {@code broker.requests} carries maintask, subtask and outcome; a subtask the route table does
 * not know is counted as {@code UNKNOWN} so frontend typos cannot explode the tag space. The old
 * {@code broker.gelen.istekler} counter keeps its name so existing dashboards keep working.
 */
@Component
public class BrokerMetrics {

    public static final String UNKNOWN = "UNKNOWN";

    private final MeterRegistry registry;
    private final BrokerProperties.LegacyCounter legacy;

    public BrokerMetrics(MeterRegistry registry, BrokerProperties properties) {
        this.registry = registry;
        this.legacy = properties.legacyCounter();
    }

    public void request(String maintask, String subtask, String outcome, Duration duration) {
        Timer.builder("broker.requests")
                .description("Broker'dan geçen istekler")
                .tag("maintask", maintask == null ? UNKNOWN : maintask)
                .tag("subtask", subtask == null ? UNKNOWN : subtask)
                .tag("outcome", outcome)
                .register(registry)
                .record(duration);
    }

    /** Login and logout are not counted, as today. */
    public void legacyIncoming(String project, String endpoint) {
        Counter.builder(legacy.name())
                .tag(legacy.projectTag(), project == null ? legacy.unknownProject() : project)
                .tag(legacy.endpointTag(), endpoint == null ? legacy.unknownEndpoint() : endpoint)
                .register(registry)
                .increment();
    }

    /** Time until Kafka acknowledged an event, or gave up. */
    public void logAck(String eventType, boolean acknowledged, Duration duration) {
        Timer.builder("broker.log.ack")
                .tag("event_type", eventType)
                .tag("result", acknowledged ? "ack" : "failed")
                .register(registry)
                .record(duration);
    }

    public void logUnavailable() {
        registry.counter("broker.log.unavailable").increment();
    }

    public void skipped(String kind) {
        registry.counter("broker.routes.skipped", "kind", kind).increment();
    }

    public void configVersion(Supplier<Number> version) {
        Gauge.builder("broker.routes.config.version", version).register(registry);
    }

    public void spoolFiles(Supplier<Number> files) {
        Gauge.builder("broker.log.spool.files", files)
                .description("Kafka'ya gönderilmeyi bekleyen spool dosyaları")
                .register(registry);
    }
}
