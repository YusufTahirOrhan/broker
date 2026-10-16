package com.acme.broker.core.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.acme.broker.core.json.Json;

import tools.jackson.databind.JsonNode;

/** The record is the legacy broker's: both brokers and the login service must read and write the same bytes. */
class SessionRecordTest {

    private final SessionRecordCodec codec = SessionRecordCodec.DEFAULT;

    @Test
    void theRecordIsAnUntypedJsonObjectUnderUserSession() {
        SessionRecord record = new SessionRecord("u12345", "5f0c7c1e-1d7a-4c2e-9f55-3b2a8e4d6c10", "10.20.30.40",
                "Mozilla/5.0", List.of("LOGPROJECT_TEMEL", "SORGU_TEMEL"));

        assertThat(codec.key("u12345")).isEqualTo("user:session:u12345");
        JsonNode written = Json.parse(codec.write(record));
        assertThat(Json.fieldNames(written)).containsExactlyInAnyOrder("uid", "X-Token", "X-Clientip", "X-User-Agent",
                "permissions");
        assertThat(written).isEqualTo(Json.parse("""
                {"uid": "u12345", "X-Token": "5f0c7c1e-1d7a-4c2e-9f55-3b2a8e4d6c10", "X-Clientip": "10.20.30.40",
                 "X-User-Agent": "Mozilla/5.0", "permissions": ["LOGPROJECT_TEMEL", "SORGU_TEMEL"]}
                """));
        assertThat(written.toString()).doesNotContain("@class");
        assertThat(codec.read(codec.write(record))).contains(record);
    }

    @Test
    void emptyValuesAreWrittenAsNull() {
        JsonNode written = Json.parse(codec.write(new SessionRecord("u1", "t", "", null, null)));

        assertThat(written.get("X-Clientip").isNull()).isTrue();
        assertThat(written.get("X-User-Agent").isNull()).isTrue();
        assertThat(written.get("permissions").isNull()).isTrue();
    }

    @Test
    void aRecordTheLegacyBrokerWroteIsReadInAnyFieldOrder() {
        String legacy = """
                {"permissions":["SORGU_TEMEL"],"X-User-Agent":"agent","uid":"u7","X-Clientip":"10.0.0.1","X-Token":"tok"}
                """;

        SessionRecord record = codec.read(legacy).orElseThrow();
        assertThat(record).isEqualTo(new SessionRecord("u7", "tok", "10.0.0.1", "agent", List.of("SORGU_TEMEL")));
        assertThat(codec.read("not json")).isEmpty();
        assertThat(codec.read("[1]")).isEmpty();
        assertThat(codec.read(null)).isEmpty();
    }

    @Test
    void aSessionMatchesOnlyItsOwnTokenAndClient() {
        SessionRecord record = new SessionRecord("u1", "tok", "10.0.0.1", "agent", List.of());

        assertThat(record.matches("tok", "10.0.0.1", "agent")).isTrue();
        assertThat(record.matches("TOK", "10.0.0.1", "agent")).isFalse();
        assertThat(record.matches("tok", "10.0.0.2", "agent")).isFalse();
        assertThat(record.matches("tok", "10.0.0.1", "Agent")).isFalse();
        assertThat(record.matches(null, "10.0.0.1", "agent")).isFalse();
        assertThat(record.matches("tok", null, "agent")).isFalse();
        assertThat(record.matches("tok", "10.0.0.1", null)).isFalse();
        assertThat(new SessionRecord("u1", "tok", null, null, null).matches("tok", null, null)).isFalse();
        assertThat(record.sameClient("10.0.0.1", "agent")).isTrue();
        assertThat(record.sameClient("10.0.0.1", "other")).isFalse();
    }

    @Test
    void keyPatternAndFieldNamesComeFromSettings() {
        SessionRecordCodec custom = new SessionRecordCodec("oturum:{uid}", "kullanici", null, null, null, "yetkiler");
        SessionRecord record = new SessionRecord("u1", "t", "ip", "ua", List.of("A"));

        assertThat(custom.key("u1")).isEqualTo("oturum:u1");
        assertThat(Json.fieldNames(Json.parse(custom.write(record))))
                .containsExactlyInAnyOrder("kullanici", "X-Token", "X-Clientip", "X-User-Agent", "yetkiler");
        assertThat(custom.read(custom.write(record))).contains(record);
    }
}
