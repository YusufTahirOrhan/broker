package com.acme.broker.core.naming;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class NamesTest {

    private final Locale original = Locale.getDefault();

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(original);
    }

    @Test
    void keyIgnoresTheTurkishDefaultLocale() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        assertThat("GET_ID".toLowerCase()).isEqualTo("get_ıd");
        assertThat(Names.key("GET_ID")).isEqualTo("get_id");
        assertThat(Names.sameName("LogProject", "logproject")).isTrue();
        assertThat(Names.sameName("GET_ID", "get_id")).isTrue();
    }

    @Test
    void subtaskNamesAreSnakeCase() {
        assertThat(Names.isValidSubtask("get_order")).isTrue();
        assertThat(Names.isValidSubtask("get_order_2")).isTrue();
        assertThat(Names.isValidSubtask("getOrder")).isFalse();
        assertThat(Names.isValidSubtask("get__order")).isFalse();
        assertThat(Names.isValidSubtask("_get")).isFalse();
        assertThat(Names.isValidSubtask("2get")).isFalse();
    }

    @Test
    void prefixesFollowTheDatabaseRule() {
        assertThat(Names.isValidPrefix("LOGPROJECT")).isTrue();
        assertThat(Names.isValidPrefix("HR_PORTAL")).isTrue();
        assertThat(Names.isValidPrefix("HR_")).isFalse();
        assertThat(Names.isValidPrefix("logproject")).isFalse();
        assertThat(Names.isValidPrefix("1ABC")).isFalse();
    }

    @Test
    void overlappingPrefixesWouldShareValidPermissions() {
        assertThat(Names.prefixesOverlap("HR", "HR_PORTAL")).isTrue();
        assertThat(Names.prefixesOverlap("HR_PORTAL", "HR")).isTrue();
        assertThat(Names.prefixesOverlap("HR", "HR")).isTrue();
        assertThat(Names.prefixesOverlap("HR", "HRX")).isFalse();
        assertThat(Names.prefixesOverlap("ORDER", "LOGPROJECT")).isFalse();
    }

    @Test
    void permissionBelongsToItsPrefix() {
        assertThat(Names.belongsTo("LOGPROJECT_TEMEL", "LOGPROJECT")).isTrue();
        assertThat(Names.belongsTo("LOGPROJECT_", "LOGPROJECT")).isFalse();
        assertThat(Names.belongsTo("LOGPROJECTX_TEMEL", "LOGPROJECT")).isFalse();
        assertThat(Names.ownerPrefix("HR_ADMIN", List.of("LOGPROJECT", "HR"))).contains("HR");
        assertThat(Names.ownerPrefix("CRM_AGENT", List.of("LOGPROJECT", "HR"))).isEmpty();
    }
}
