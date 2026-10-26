package com.acme.yapi.runtime.log;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.logging.EventCodec;
import com.acme.yapi.core.logging.LogEvent;

/** Local development ({@code yapi.log-sink=console}): events go to the console, not to DEV's Kafka. */
public class ConsoleLogPublisher implements LogPublisher {

    private static final Logger log = LoggerFactory.getLogger("yapi.events");

    @Override
    public CompletableFuture<?> publish(LogEvent event) {
        log.info("{}", Json.write(EventCodec.toJson(event)));
        return CompletableFuture.completedFuture(null);
    }
}
