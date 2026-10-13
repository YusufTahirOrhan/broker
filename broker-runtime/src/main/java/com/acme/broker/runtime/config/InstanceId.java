package com.acme.broker.runtime.config;

import java.security.SecureRandom;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

/** This instance's name: {@code broker.instance-id}, else the pod name ({@code HOSTNAME}), else a random one. */
@Component
public class InstanceId {

    private final String value;

    public InstanceId(BrokerProperties properties) {
        String configured = properties.instanceId();
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("HOSTNAME");
        }
        if (configured == null || configured.isBlank()) {
            byte[] random = new byte[3];
            new SecureRandom().nextBytes(random);
            configured = "broker-" + HexFormat.of().formatHex(random);
        }
        this.value = configured;
    }

    public String value() {
        return value;
    }
}
