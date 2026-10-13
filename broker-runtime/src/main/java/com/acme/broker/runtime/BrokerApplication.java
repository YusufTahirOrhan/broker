package com.acme.broker.runtime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The broker: one endpoint, {@code POST /broker}. Routes come from memory, permissions from one Redis read, and no
 * backend is called and no response returned before the log events are acknowledged by Kafka.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class BrokerApplication {

    public static void main(String[] args) {
        SpringApplication.run(BrokerApplication.class, args);
    }
}
