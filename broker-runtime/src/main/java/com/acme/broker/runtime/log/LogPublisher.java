package com.acme.broker.runtime.log;

import java.util.concurrent.CompletableFuture;

import com.acme.broker.core.logging.LogEvent;

/** Writes one log event; the future completes when the event is durable. */
public interface LogPublisher {

    CompletableFuture<?> publish(LogEvent event);
}
