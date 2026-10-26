package com.acme.yapi.sink.clickhouse;

import java.util.List;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.acme.yapi.sink.config.SinkProperties;
import com.acme.yapi.sink.kafka.BatchProcessor;
import com.acme.yapi.sink.kafka.DeadLetters;

/** Consumer group {@code yapi-logs-clickhouse}: {@code yapi_logs.yapi_log}. */
@Component
@ConditionalOnBooleanProperty("yapi.sink.clickhouse.enabled")
public class ClickHouseSink {

    public static final String GROUP = "yapi-logs-clickhouse";

    private final BatchProcessor processor;

    public ClickHouseSink(SinkProperties properties, DeadLetters deadLetters) {
        this.processor = new BatchProcessor(new ClickHouseWriter(properties.clickhouse()), deadLetters);
    }

    @KafkaListener(id = GROUP, groupId = GROUP, topics = "${yapi.sink.topic:yapi.logs.v1}", batch = "true")
    public void consume(List<ConsumerRecord<String, byte[]>> records) {
        processor.process(records);
    }
}
