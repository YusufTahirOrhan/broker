package com.acme.broker.sink.clickhouse;

import java.util.List;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.acme.broker.sink.config.SinkProperties;
import com.acme.broker.sink.kafka.BatchProcessor;
import com.acme.broker.sink.kafka.DeadLetters;

/** Consumer group {@code broker-logs-clickhouse}: {@code broker_logs.broker_log}. */
@Component
@ConditionalOnBooleanProperty("broker.sink.clickhouse.enabled")
public class ClickHouseSink {

    public static final String GROUP = "broker-logs-clickhouse";

    private final BatchProcessor processor;

    public ClickHouseSink(SinkProperties properties, DeadLetters deadLetters) {
        this.processor = new BatchProcessor(new ClickHouseWriter(properties.clickhouse()), deadLetters);
    }

    @KafkaListener(id = GROUP, groupId = GROUP, topics = "${broker.sink.topic:broker.logs.v1}", batch = "true")
    public void consume(List<ConsumerRecord<String, byte[]>> records) {
        processor.process(records);
    }
}
