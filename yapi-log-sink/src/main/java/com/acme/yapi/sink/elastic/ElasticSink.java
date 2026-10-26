package com.acme.yapi.sink.elastic;

import java.util.List;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.acme.yapi.sink.config.SinkProperties;
import com.acme.yapi.sink.kafka.BatchProcessor;
import com.acme.yapi.sink.kafka.DeadLetters;

/** Consumer group {@code yapi-logs-elastic}: the legacy {@code requests_yyyy-MM} documents. */
@Component
@ConditionalOnBooleanProperty("yapi.sink.elastic.enabled")
public class ElasticSink {

    public static final String GROUP = "yapi-logs-elastic";

    private final BatchProcessor processor;

    public ElasticSink(SinkProperties properties, DeadLetters deadLetters) {
        this.processor = new BatchProcessor(new ElasticBulkWriter(properties.elastic()), deadLetters);
    }

    @KafkaListener(id = GROUP, groupId = GROUP, topics = "${yapi.sink.topic:yapi.logs.v1}", batch = "true")
    public void consume(List<ConsumerRecord<String, byte[]>> records) {
        processor.process(records);
    }
}
