package com.acme.yapi.runtime.log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.logging.EventCodec;
import com.acme.yapi.core.logging.LogEvent;
import com.acme.yapi.runtime.config.YapiProperties;
import com.acme.yapi.runtime.metrics.YapiMetrics;

/**
 * Local record of requests that got 500 because Kafka did not acknowledge their events. Each request's events are
 * one file of JSON lines, written atomically; a background task resends files in order and deletes them once
 * Kafka has them. A resend may duplicate an event that did reach Kafka; the sinks deduplicate. Losing the spool
 * loses only this completion, never the guarantee that nothing passed unlogged.
 */
@Component
public class Spool {

    private static final Logger log = LoggerFactory.getLogger(Spool.class);
    private static final String SUFFIX = ".jsonl";

    private final Path directory;
    private final LogPublisher publisher;
    private final long ackTimeoutMillis;
    private final AtomicLong sequence = new AtomicLong();

    public Spool(YapiProperties properties, LogPublisher publisher, YapiMetrics metrics) {
        this.directory = properties.spool().directory();
        this.publisher = publisher;
        this.ackTimeoutMillis = properties.kafka().ackTimeout().toMillis();
        metrics.spoolFiles(this::size);
    }

    public void store(List<LogEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        StringBuilder lines = new StringBuilder();
        for (LogEvent event : events) {
            lines.append(Json.write(EventCodec.toJson(event))).append('\n');
        }
        String name = "%013d-%06d-%s".formatted(System.currentTimeMillis(), sequence.incrementAndGet() % 1_000_000,
                events.getFirst().requestId());
        try {
            Files.createDirectories(directory);
            Path temporary = directory.resolve(name + ".tmp");
            Files.writeString(temporary, lines, StandardCharsets.UTF_8);
            Files.move(temporary, directory.resolve(name + SUFFIX), StandardCopyOption.ATOMIC_MOVE);
            log.warn("Log olayları spool'a yazıldı: {} ({} olay)", name, events.size());
        } catch (IOException e) {
            log.error("Spool'a yazılamadı; olaylar yalnızca bu logda: {}", lines, e);
        }
    }

    public int size() {
        try (Stream<Path> files = Files.list(directory)) {
            return (int) files.filter(file -> file.getFileName().toString().endsWith(SUFFIX)).count();
        } catch (IOException e) {
            return 0;
        }
    }

    /** Sends spooled files oldest first; stops at the first failure and tries again on the next run. */
    @Scheduled(fixedDelayString = "${yapi.spool.resend-interval:10s}", initialDelayString = "${yapi.spool.resend-interval:10s}")
    public synchronized void resend() {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> listing = Files.list(directory)) {
            listing.filter(file -> file.getFileName().toString().endsWith(SUFFIX)).sorted().forEach(files::add);
        } catch (IOException e) {
            return;
        }
        for (Path file : files) {
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) {
                        publisher.publish(EventCodec.fromJson(Json.parse(line))).get(ackTimeoutMillis, TimeUnit.MILLISECONDS);
                    }
                }
                Files.delete(file);
                log.info("Spool dosyası Kafka'ya gönderildi: {}", file.getFileName());
            } catch (ExecutionException | TimeoutException e) {
                log.debug("Spool gönderimi bekliyor: Kafka hâlâ yazmıyor");
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException | RuntimeException e) {
                log.error("Spool dosyası okunamadı, atlanıyor: {}", file, e);
            }
        }
    }
}
