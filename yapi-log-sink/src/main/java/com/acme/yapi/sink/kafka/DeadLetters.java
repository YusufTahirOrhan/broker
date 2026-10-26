package com.acme.yapi.sink.kafka;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.acme.yapi.sink.config.SinkProperties;

/**
 * {@code yapi.logs.v1.dlq}: messages that cannot be processed, with where they came from and why, so one bad
 * message never stops its partition. A message is moved only when the DLQ acknowledged it; otherwise the batch is
 * retried.
 */
@Component
public class DeadLetters {

    public static final String SINK_HEADER = "yapi.dlq.sink";
    public static final String REASON_HEADER = "yapi.dlq.reason";
    public static final String ORIGIN_HEADER = "yapi.dlq.origin";

    private static final Logger log = LoggerFactory.getLogger(DeadLetters.class);

    private final KafkaTemplate<String, byte[]> kafka;
    private final String topic;

    public DeadLetters(KafkaTemplate<String, byte[]> kafka, SinkProperties properties) {
        this.kafka = kafka;
        this.topic = properties.dlqTopic();
    }

    public record Letter(ConsumerRecord<String, byte[]> record, String reason) {
    }

    public void send(String sink, List<Letter> letters) {
        if (letters.isEmpty()) {
            return;
        }
        List<CompletableFuture<?>> sent = new ArrayList<>();
        for (Letter letter : letters) {
            ConsumerRecord<String, byte[]> record = letter.record();
            ProducerRecord<String, byte[]> dead = new ProducerRecord<>(topic, record.key(), record.value());
            dead.headers().add(SINK_HEADER, sink.getBytes(StandardCharsets.UTF_8));
            dead.headers().add(REASON_HEADER, truncate(letter.reason()).getBytes(StandardCharsets.UTF_8));
            dead.headers().add(ORIGIN_HEADER, (record.topic() + "/" + record.partition() + "@" + record.offset())
                    .getBytes(StandardCharsets.UTF_8));
            sent.add(kafka.send(dead));
            log.warn("DLQ'ya taşındı ({}): {}/{}@{}: {}", sink, record.topic(), record.partition(), record.offset(),
                    letter.reason());
        }
        try {
            CompletableFuture.allOf(sent.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new SinkUnavailableException("DLQ'ya yazılamadı", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SinkUnavailableException("DLQ'ya yazma kesildi", e);
        }
    }

    private static String truncate(String reason) {
        String value = reason == null ? "" : reason;
        return value.length() > 2_000 ? value.substring(0, 2_000) : value;
    }
}
