package com.acme.broker.runtime.log;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.acme.broker.runtime.config.BrokerProperties;

/**
 * Fetches the topic's metadata before the instance takes traffic. Without it the first requests would wait for
 * metadata, hit {@code max.block.ms=50} and get 500.
 */
@Component
public class KafkaWarmup implements ApplicationRunner, Ordered {

    private static final Logger log = LoggerFactory.getLogger(KafkaWarmup.class);

    private final KafkaTemplate<String, byte[]> kafka;
    private final BrokerProperties properties;

    public KafkaWarmup(KafkaTemplate<String, byte[]> kafka, BrokerProperties properties) {
        this.kafka = kafka;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws InterruptedException {
        BrokerProperties.Kafka settings = properties.kafka();
        if (properties.consoleLog() || !settings.warmup()) {
            return;
        }
        long deadline = System.nanoTime() + settings.warmupTimeout().toNanos();
        RuntimeException last = null;
        while (System.nanoTime() < deadline) {
            try {
                int partitions = kafka.partitionsFor(settings.topic()).size();
                log.info("Kafka metadata'sı hazır: {} ({} partition)", settings.topic(), partitions);
                return;
            } catch (RuntimeException e) {
                last = e;
                Thread.sleep(Duration.ofMillis(250));
            }
        }
        throw new IllegalStateException("Kafka topic'inin metadata'sı alınamadı: " + settings.topic(), last);
    }

    @Override
    public int getOrder() {
        return 50;
    }
}
