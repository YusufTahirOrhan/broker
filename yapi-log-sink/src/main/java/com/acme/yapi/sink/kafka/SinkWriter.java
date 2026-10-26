package com.acme.yapi.sink.kafka;

import java.util.List;

import com.acme.yapi.core.logging.LogEvent;

/** Writes a batch of events to one target. */
public interface SinkWriter {

    /** Name used in DLQ headers and logs: {@code elastic} or {@code clickhouse}. */
    String name();

    /**
     * @return events the target refused for good, by their index in {@code events}
     * @throws SinkUnavailableException when the target cannot take writes; nothing is committed
     */
    List<Rejection> write(List<LogEvent> events);
}
