package com.acme.broker.runtime.log;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.acme.broker.core.logging.LogEvent;
import com.acme.broker.runtime.config.BrokerProperties;
import com.acme.broker.runtime.metrics.BrokerMetrics;

/**
 * Nothing passes before its log is durable: a backend is called only after the REQUEST event is acknowledged and a
 * response leaves only after the RESPONSE event is. Events that were not acknowledged go to the spool.
 */
@Component
public class LogGate {

    private static final Logger log = LoggerFactory.getLogger(LogGate.class);

    private final LogPublisher publisher;
    private final Spool spool;
    private final Duration ackTimeout;
    private final BrokerMetrics metrics;

    public LogGate(LogPublisher publisher, Spool spool, BrokerProperties properties, BrokerMetrics metrics) {
        this.publisher = publisher;
        this.spool = spool;
        this.ackTimeout = properties.kafka().ackTimeout();
        this.metrics = metrics;
    }

    /**
     * Sends the events together (same key, so they keep their order) and waits for every acknowledgement.
     *
     * @return the events that were not acknowledged; empty when all are durable
     */
    public List<LogEvent> write(LogEvent... events) {
        long started = System.nanoTime();
        List<CompletableFuture<?>> futures = new ArrayList<>(events.length);
        for (LogEvent event : events) {
            futures.add(publisher.publish(event));
        }
        List<LogEvent> failed = new ArrayList<>();
        for (int i = 0; i < events.length; i++) {
            boolean acknowledged = await(futures.get(i), started);
            metrics.logAck(events[i].eventType().name(), acknowledged, Duration.ofNanos(System.nanoTime() - started));
            if (!acknowledged) {
                failed.add(events[i]);
            }
        }
        return failed;
    }

    /** Keeps events that could not be written so a background task completes the record later. */
    public void spool(List<LogEvent> events) {
        metrics.logUnavailable();
        spool.store(events);
    }

    private boolean await(CompletableFuture<?> future, long started) {
        long remaining = ackTimeout.toNanos() - (System.nanoTime() - started);
        try {
            future.get(Math.max(remaining, 0), TimeUnit.NANOSECONDS);
            return true;
        } catch (ExecutionException | TimeoutException e) {
            log.warn("Log olayı Kafka'ya yazılamadı: {}", e.getCause() == null ? e.toString() : e.getCause().toString());
            future.cancel(false);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
