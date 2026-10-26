package com.acme.yapi.envadmin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The environment's Admin API. The central admin reaches an environment only through it, over mTLS; it is the only
 * writer of the runtime DB and the one that signals brokers through Redis.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class EnvAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(EnvAdminApplication.class, args);
    }
}
