package com.acme.yapi.core.ids;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/** Request IDs (UUIDv7), trace IDs and the derived IDs of legacy parameter documents. */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    /** UUIDv7 (RFC 9562): 48-bit Unix milliseconds, then random bits; sorts by time. */
    public static UUID uuidV7() {
        return uuidV7(System.currentTimeMillis());
    }

    public static UUID uuidV7(long epochMillis) {
        byte[] random = new byte[10];
        RANDOM.nextBytes(random);
        long msb = (epochMillis & 0xFFFF_FFFF_FFFFL) << 16;
        msb |= 0x7000L;                                            // version 7
        msb |= ((random[0] & 0x0FL) << 8) | (random[1] & 0xFFL);  // rand_a, 12 bits
        long lsb = 0;
        for (int i = 2; i < 10; i++) {
            lsb = (lsb << 8) | (random[i] & 0xFFL);
        }
        lsb = (lsb & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L; // variant 10
        return new UUID(msb, lsb);
    }

    /** Time encoded in a UUIDv7. */
    public static Instant timeOf(UUID uuidV7) {
        return Instant.ofEpochMilli(uuidV7.getMostSignificantBits() >>> 16);
    }

    /** 32 hex characters, the W3C trace-id format. */
    public static String traceId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** 16 hex characters, the W3C parent-id format. */
    public static String spanId() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * Name-based UUID of a legacy parameter document: the same request, field and position always give the same
     * ID, so a replayed event overwrites the document instead of duplicating it.
     */
    public static String parameterDocumentId(String requestId, String field, int position) {
        String name = requestId + "|" + field + "|" + position;
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
