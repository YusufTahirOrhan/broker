package com.acme.yapi.login;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The login service: only the broker calls it, through the anonymous routes {@code giris/login} and
 * {@code giris/logout}; the Koordinatör calls its internal logout directly.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class LoginApplication {

    public static void main(String[] args) {
        SpringApplication.run(LoginApplication.class, args);
    }
}
