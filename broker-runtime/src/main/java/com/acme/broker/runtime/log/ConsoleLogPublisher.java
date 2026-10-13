package com.acme.broker.runtime.log;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.acme.broker.core.json.Json;
import com.acme.broker.core.logging.EventCodec;
import com.acme.broker.core.logging.LogEvent;

/** Local development ({@code broker.log-sink=console}): events go to the console, not to DEV's Kafka. */
public class ConsoleLogPublisher implements LogPublisher {

    private static final Logger log = LoggerFactory.getLogger("broker.events");

    @Override
    public CompletableFuture<?> publish(LogEvent event) {
        log.info("{}", Json.write(EventCodec.toJson(event)));
        return CompletableFuture.completedFuture(null);
    }
}
