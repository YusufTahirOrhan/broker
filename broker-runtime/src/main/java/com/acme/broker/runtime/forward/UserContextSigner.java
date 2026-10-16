package com.acme.broker.runtime.forward;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.springframework.stereotype.Component;

import com.acme.broker.core.context.UserContextToken;
import com.acme.broker.runtime.config.BrokerProperties;

/**
 * The signed user context services can trust instead of {@code X-uid} ({@link UserContextToken}). Permissions are
 * not sent; data-level authorization stays in the service.
 */
@Component
public class UserContextSigner {

    private final byte[] secret;
    private final String header;

    public UserContextSigner(BrokerProperties properties) {
        String configured = properties.forward().userContextSecret();
        this.secret = configured == null || configured.isEmpty() ? null : configured.getBytes(StandardCharsets.UTF_8);
        this.header = properties.forward().userContextHeader();
    }

    public boolean enabled() {
        return secret != null;
    }

    public String header() {
        return header;
    }

    public String sign(String userId, String requestId, Instant issuedAt) {
        return UserContextToken.sign(secret, userId, requestId, issuedAt);
    }
}
