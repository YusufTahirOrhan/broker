package com.acme.yapi.core.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class UserContextTokenTest {

    private static final byte[] SECRET = "k3y".getBytes(StandardCharsets.UTF_8);
    private static final Instant NOW = Instant.parse("2026-10-16T09:00:00Z");
    private static final Duration MINUTE = Duration.ofSeconds(60);

    @Test
    void aSignedContextVerifiesWithTheSameKeyWhileFresh() {
        String token = UserContextToken.sign(SECRET, "u12345", "req-1", NOW);

        assertThat(token).startsWith("v1.").matches("v1\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
        assertThat(UserContextToken.verify(SECRET, token, NOW.plusSeconds(59), MINUTE))
                .contains(new UserContextToken.Payload("u12345", "req-1", NOW));
        assertThat(UserContextToken.verify(SECRET, token, NOW.plusSeconds(61), MINUTE)).isEmpty();
        assertThat(UserContextToken.verify(SECRET, token, NOW.minusSeconds(61), MINUTE)).isEmpty();
        assertThat(UserContextToken.verify("other".getBytes(StandardCharsets.UTF_8), token, NOW, MINUTE)).isEmpty();
    }

    @Test
    void aSessionOperationHasNoUser() {
        String token = UserContextToken.sign(SECRET, null, "req-2", NOW);

        assertThat(UserContextToken.verify(SECRET, token, NOW, MINUTE).orElseThrow().userId()).isNull();
    }

    @Test
    void tamperedOrMalformedTokensAreRefused() {
        String token = UserContextToken.sign(SECRET, "u1", "r", NOW);
        String[] parts = token.split("\\.");
        String forged = UserContextToken.sign(SECRET, "admin", "r", NOW).split("\\.")[1];

        assertThat(UserContextToken.verify(SECRET, parts[0] + "." + forged + "." + parts[2], NOW, MINUTE)).isEmpty();
        assertThat(UserContextToken.verify(SECRET, "v2." + parts[1] + "." + parts[2], NOW, MINUTE)).isEmpty();
        for (String broken : new String[] {null, "", "v1", "v1.x", "v1.!!.??", "v1.e30.AAAA"}) {
            assertThat(UserContextToken.verify(SECRET, broken, NOW, MINUTE)).as(String.valueOf(broken)).isEmpty();
        }
    }
}
