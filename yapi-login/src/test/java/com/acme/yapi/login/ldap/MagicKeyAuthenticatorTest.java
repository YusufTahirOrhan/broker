package com.acme.yapi.login.ldap;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class MagicKeyAuthenticatorTest {

    private final MagicKeyAuthenticator dev = new MagicKeyAuthenticator("Dev-Anahtar-2026");

    @Test
    void anyUidSignsInWithTheKeyAndEveryAttemptIsLoggedWithoutIt(CapturedOutput output) {
        assertThat(dev.authenticate("12345", "Dev-Anahtar-2026")).isTrue();
        assertThat(dev.authenticate("67890", "Dev-Anahtar-2026")).isTrue();
        assertThat(dev.authenticate("12345", "dev-anahtar-2026")).isFalse();
        assertThat(dev.authenticate("12345", "Dev-Anahtar-2026 ")).isFalse();
        assertThat(dev.authenticate("12345", null)).isFalse();
        assertThat(dev.authenticate(" ", "Dev-Anahtar-2026")).isFalse();
        assertThat(dev.authenticate(null, "Dev-Anahtar-2026")).isFalse();

        assertThat(output.getAll().lines().filter(line -> line.contains("INFO") && line.contains("uid=12345")))
                .hasSize(4);
        assertThat(output.getAll()).contains("uid=67890").doesNotContain("Dev-Anahtar-2026");
    }
}
