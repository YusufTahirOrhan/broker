package com.acme.yapi.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.acme.yapi.core.session.SessionRecordCodec;

/** Names other systems see: ours carry the project's name, the legacy broker's stay as they are. */
class SharedNamesTest {

    @Test
    void ourRedisNamesAreYapisAndTheLegacySessionKeyStays() {
        assertThat(RuntimeSignals.ROUTES_CHANGED_CHANNEL).isEqualTo("yapi:routes-changed");
        assertThat(RuntimeSignals.LOADED_KEY_PREFIX).isEqualTo("yapi:loaded:");
        assertThat(SessionRecordCodec.DEFAULT.key("u42")).isEqualTo("user:session:u42");
    }
}
