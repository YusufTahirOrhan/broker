package com.acme.yapi.sink.kafka;

import java.util.ArrayList;
import java.util.List;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.acme.yapi.core.logging.EventCodec;
import com.acme.yapi.core.logging.LogEvent;

/**
 * One poll's records for one target: decode, write, move what cannot be processed to the DLQ. Returning normally
 * lets the container commit the batch's offsets; any exception keeps them uncommitted and the batch is retried.
 */
public final class BatchProcessor {

    private static final Logger log = LoggerFactory.getLogger(BatchProcessor.class);

    private final SinkWriter writer;
    private final DeadLetters deadLetters;

    public BatchProcessor(SinkWriter writer, DeadLetters deadLetters) {
        this.writer = writer;
        this.deadLetters = deadLetters;
    }

    public void process(List<ConsumerRecord<String, byte[]>> records) {
        List<DeadLetters.Letter> letters = new ArrayList<>();
        List<ConsumerRecord<String, byte[]>> decodedRecords = new ArrayList<>();
        List<LogEvent> events = new ArrayList<>();
        for (ConsumerRecord<String, byte[]> record : records) {
            try {
                LogEvent event = EventCodec.fromBytes(record.value());
                if (event.requestId() == null || event.eventType() == null || event.eventTime() == null) {
                    throw new IllegalArgumentException("request_id, event_type ya da event_time eksik");
                }
                events.add(event);
                decodedRecords.add(record);
            } catch (RuntimeException e) {
                letters.add(new DeadLetters.Letter(record, "Ayrıştırılamadı: " + e.getMessage()));
            }
        }
        if (!events.isEmpty()) {
            for (Rejection rejection : writer.write(events)) {
                letters.add(new DeadLetters.Letter(decodedRecords.get(rejection.index()), rejection.reason()));
            }
        }
        deadLetters.send(writer.name(), letters);
        log.debug("{}: {} olay yazıldı, {} DLQ'ya", writer.name(), events.size(), letters.size());
    }
}
