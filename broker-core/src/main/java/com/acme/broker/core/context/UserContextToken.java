package com.acme.broker.core.context;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.acme.broker.core.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The signed user context the broker sends to services ({@code X-User-Context}):
 * {@code v1.<base64url(payload)>.<base64url(HMAC-SHA256(secret, "v1." + base64url(payload)))>} with payload
 * {@code {"userId": "...", "requestId": "...", "issuedAt": 1760000000}}. {@code userId} is null for a session
 * operation (login, logout), which has no user yet.
 */
public final class UserContextToken {

    private static final String VERSION = "v1";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    public record Payload(String userId, String requestId, Instant issuedAt) {
    }

    private UserContextToken() {
    }

    public static String sign(byte[] secret, String userId, String requestId, Instant issuedAt) {
        ObjectNode payload = Json.object();
        payload.put("userId", userId);
        payload.put("requestId", requestId);
        payload.put("issuedAt", issuedAt.getEpochSecond());
        String body = VERSION + "." + ENCODER.encodeToString(Json.writeBytes(payload));
        return body + "." + ENCODER.encodeToString(hmac(secret, body));
    }

    /**
     * The payload of a token signed with {@code secret} and issued at most {@code maxAge} before or after
     * {@code now}; empty for anything else.
     */
    public static Optional<Payload> verify(byte[] secret, String token, Instant now, Duration maxAge) {
        if (token == null) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            return Optional.empty();
        }
        byte[] signature;
        byte[] payloadBytes;
        try {
            signature = DECODER.decode(parts[2]);
            payloadBytes = DECODER.decode(parts[1]);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(signature, hmac(secret, parts[0] + "." + parts[1]))) {
            return Optional.empty();
        }
        JsonNode payload = Json.tryParse(payloadBytes);
        JsonNode issued = payload == null ? null : payload.get("issuedAt");
        if (issued == null || !issued.canConvertToLong()) {
            return Optional.empty();
        }
        Instant issuedAt = Instant.ofEpochSecond(issued.longValue());
        if (Duration.between(issuedAt, now).abs().compareTo(maxAge) > 0) {
            return Optional.empty();
        }
        return Optional.of(new Payload(Json.stringField(payload, "userId"), Json.stringField(payload, "requestId"),
                issuedAt));
    }

    private static byte[] hmac(byte[] secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
