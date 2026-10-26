package com.acme.yapi.runtime.log;

import java.util.concurrent.CompletableFuture;

import com.acme.yapi.core.logging.LogEvent;

/** Writes one log event; the future completes when the event is durable. */
public interface LogPublisher {

    CompletableFuture<?> publish(LogEvent event);
}
