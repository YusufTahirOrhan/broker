package com.acme.broker.runtime.forward;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.acme.broker.core.json.Json;
import com.acme.broker.runtime.config.BrokerProperties;

import tools.jackson.databind.node.ObjectNode;

/**
 * The signed user context services can trust instead of {@code X-uid}:
 * {@code v1.<base64url(payload)>.<base64url(HMAC-SHA256(secret, "v1." + payload))>} with payload
 * {@code {"userId": "...", "requestId": "...", "issuedAt": 1760000000}}. Permissions are not sent; data-level
 * authorization stays in the service.
 */
@Component
public class UserContextSigner {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

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
        ObjectNode payload = Json.object();
        payload.put("userId", userId);
        payload.put("requestId", requestId);
        payload.put("issuedAt", issuedAt.getEpochSecond());
        String body = "v1." + ENCODER.encodeToString(Json.writeBytes(payload));
        return body + "." + ENCODER.encodeToString(hmac(body));
    }

    private byte[] hmac(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
