package com.acme.broker.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The central admin backend ({@code docs/frontend-guide.md}): definitions, versions, promotion and approval. It
 * reaches environments only through their Admin APIs.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class AdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminApplication.class, args);
    }
}
