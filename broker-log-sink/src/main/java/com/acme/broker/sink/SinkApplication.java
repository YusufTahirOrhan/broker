package com.acme.broker.sink;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Reads {@code broker.logs.v1} with one consumer group per target. Elastic and ClickHouse read the whole stream
 * independently; one being slow or down never holds the other back, and never the request path.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SinkApplication {

    public static void main(String[] args) {
        SpringApplication.run(SinkApplication.class, args);
    }
}
