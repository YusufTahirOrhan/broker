package com.acme.broker.sink.elastic;

import java.util.List;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.acme.broker.sink.config.SinkProperties;
import com.acme.broker.sink.kafka.BatchProcessor;
import com.acme.broker.sink.kafka.DeadLetters;

/** Consumer group {@code broker-logs-elastic}: the legacy {@code requests_yyyy-MM} documents. */
@Component
@ConditionalOnBooleanProperty("broker.sink.elastic.enabled")
public class ElasticSink {

    public static final String GROUP = "broker-logs-elastic";

    private final BatchProcessor processor;

    public ElasticSink(SinkProperties properties, DeadLetters deadLetters) {
        this.processor = new BatchProcessor(new ElasticBulkWriter(properties.elastic()), deadLetters);
    }

    @KafkaListener(id = GROUP, groupId = GROUP, topics = "${broker.sink.topic:broker.logs.v1}", batch = "true")
    public void consume(List<ConsumerRecord<String, byte[]>> records) {
        processor.process(records);
    }
}
