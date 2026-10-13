package com.acme.broker.runtime.log;

import java.util.concurrent.CompletableFuture;

import org.springframework.kafka.core.KafkaTemplate;

import com.acme.broker.core.logging.EventCodec;
import com.acme.broker.core.logging.LogEvent;

/**
 * Sends events to {@code broker.logs.v1} keyed by request ID, so REQUEST and RESPONSE land in one partition in
 * order. With {@code acks=all} and {@code min.insync.replicas=2} the acknowledgement means two replicas have it.
 */
public class KafkaLogPublisher implements LogPublisher {

    private final KafkaTemplate<String, byte[]> kafka;
    private final String topic;

    public KafkaLogPublisher(KafkaTemplate<String, byte[]> kafka, String topic) {
        this.kafka = kafka;
        this.topic = topic;
    }

    @Override
    public CompletableFuture<?> publish(LogEvent event) {
        try {
            return kafka.send(topic, event.requestId(), EventCodec.toBytes(event));
        } catch (RuntimeException e) {
            // max.block.ms ran out (no metadata or no buffer): fail the event instead of blocking the request
            return CompletableFuture.failedFuture(e);
        }
    }
}
