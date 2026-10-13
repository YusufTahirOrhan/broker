package com.acme.broker.runtime.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

import com.acme.broker.core.logging.EventCodec;
import com.acme.broker.core.logging.LogEvent;

/** Reads the log topic from the beginning and groups events by request ID. */
public final class EventCollector implements AutoCloseable {

    private final KafkaConsumer<String, byte[]> consumer;
    private final Map<String, List<LogEvent>> byRequest = new ConcurrentHashMap<>();

    public EventCollector(String brokers, String topic) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        consumer = new KafkaConsumer<>(properties, new StringDeserializer(), new ByteArrayDeserializer());
        List<TopicPartition> partitions = consumer.partitionsFor(topic, Duration.ofSeconds(10)).stream()
                .map(info -> new TopicPartition(topic, info.partition()))
                .toList();
        consumer.assign(partitions);
        consumer.seekToBeginning(partitions);
    }

    /** Waits until {@code count} events of the request arrived; fails after ten seconds. */
    public synchronized List<LogEvent> await(String requestId, int count) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            List<LogEvent> events = byRequest.getOrDefault(requestId, List.of());
            if (events.size() >= count) {
                return List.copyOf(events);
            }
            for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(200))) {
                LogEvent event = EventCodec.fromBytes(record.value());
                byRequest.computeIfAbsent(event.requestId(), id -> new ArrayList<>()).add(event);
            }
        }
        throw new AssertionError("Olaylar gelmedi: " + requestId + " (" + byRequest.getOrDefault(requestId, List.of())
                .size() + "/" + count + ")");
    }

    public LogEvent awaitType(String requestId, LogEvent.EventType type) {
        return await(requestId, 2).stream().filter(event -> event.eventType() == type).findFirst().orElseThrow();
    }

    @Override
    public synchronized void close() {
        consumer.close();
    }
}
