package com.acme.yapi.core.ids;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void uuidV7CarriesVersionVariantAndTime() {
        long millis = Instant.parse("2026-10-13T12:00:00.123Z").toEpochMilli();
        UUID uuid = Ids.uuidV7(millis);
        assertThat(uuid.version()).isEqualTo(7);
        assertThat(uuid.variant()).isEqualTo(2);
        assertThat(Ids.timeOf(uuid).toEpochMilli()).isEqualTo(millis);
    }

    @Test
    void laterIdsSortAfterEarlierOnes() {
        UUID first = Ids.uuidV7(1_000);
        UUID second = Ids.uuidV7(2_000);
        assertThat(first.toString().compareTo(second.toString())).isNegative();
    }

    @Test
    void traceIdsUseTheW3cFormat() {
        assertThat(Ids.traceId()).hasSize(32).matches("[0-9a-f]+");
        assertThat(Ids.spanId()).hasSize(16).matches("[0-9a-f]+");
    }

    @Test
    void parameterDocumentIdsAreStable() {
        assertThat(Ids.parameterDocumentId("r", "filtre", 0)).isEqualTo(Ids.parameterDocumentId("r", "filtre", 0))
                .isNotEqualTo(Ids.parameterDocumentId("r", "filtre", 1));
    }
}
