package com.acme.broker.sink.config;

import java.time.Duration;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param retryInterval wait between attempts while a target is down; the batch is retried until it succeeds, so
 *                      nothing is skipped as long as Kafka retention lasts
 */
@ConfigurationProperties("broker.sink")
public record SinkProperties(String topic, String dlqTopic, Duration retryInterval, Elastic elastic,
                             ClickHouse clickhouse) {

    public SinkProperties {
        topic = topic == null ? "broker.logs.v1" : topic;
        dlqTopic = dlqTopic == null ? "broker.logs.v1.dlq" : dlqTopic;
        retryInterval = retryInterval == null ? Duration.ofSeconds(5) : retryInterval;
        elastic = elastic == null ? new Elastic(false, null, null, null, null, null, null, null, null) : elastic;
        clickhouse = clickhouse == null ? new ClickHouse(false, null, null, null, null, null) : clickhouse;
    }

    /**
     * @param indexZone the time zone the old broker used to pick {@code requests_yyyy-MM}; to be confirmed from its code
     * @param apiKey    sent as {@code Authorization: ApiKey ...}; else basic auth when a username is set
     */
    public record Elastic(boolean enabled, String url, String indexPrefix, ZoneId indexZone, String apiKey,
                          String username, String password, Duration timeout, Integer retryOnConflict) {
        public Elastic {
            indexPrefix = indexPrefix == null ? "requests_" : indexPrefix;
            indexZone = indexZone == null ? ZoneId.of("UTC") : indexZone;
            timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
            retryOnConflict = retryOnConflict == null ? 3 : retryOnConflict;
        }
    }

    /** ClickHouse over its HTTP interface. The token is never stored there. */
    public record ClickHouse(boolean enabled, String url, String table, String user, String password,
                             Duration timeout) {
        public ClickHouse {
            table = table == null ? "broker_logs.broker_log" : table;
            timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        }
    }
}
