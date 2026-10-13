package com.acme.broker.envadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.acme.broker.core.testing.JsonHttp;
import com.acme.broker.core.testing.TestCertificates;
import com.acme.broker.envadmin.testing.RuntimeContainers;

/** The Admin API with the production TLS setup: client certificates are required and their subjects checked. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "broker.env-admin.allowed-client-subjects[0]=CN=broker-admin, O=Acme"
})
@ActiveProfiles("mtls")
class MutualTlsTest {

    static final TestCertificates certificates = TestCertificates.create();
    static final Path server = certificates.keyPair("server", "CN=localhost", "dns:localhost,ip:127.0.0.1");
    static final Path central = certificates.keyPair("central", "CN=broker-admin, O=Acme", null);
    static final Path intruder = certificates.keyPair("intruder", "CN=someone-else, O=Acme", null);
    static final Path serverTrust = certificates.trustStore("server-trust", central, intruder);
    static final Path clientTrust = certificates.trustStore("client-trust", server);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        RuntimeContainers.register(registry);
        registry.add("BROKER_ENV_ADMIN_KEYSTORE", () -> server.toUri().toString());
        registry.add("BROKER_ENV_ADMIN_KEYSTORE_PASSWORD", () -> TestCertificates.PASSWORD);
        registry.add("BROKER_ENV_ADMIN_TRUSTSTORE", () -> serverTrust.toUri().toString());
        registry.add("BROKER_ENV_ADMIN_TRUSTSTORE_PASSWORD", () -> TestCertificates.PASSWORD);
    }

    @LocalServerPort
    int port;

    private JsonHttp client(Path keyStore) {
        HttpClient client = HttpClient.newBuilder()
                .sslContext(TestCertificates.context(keyStore, clientTrust))
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        return new JsonHttp(client, "https://localhost:" + port);
    }

    @Test
    void theCentralCertificateIsAccepted() {
        JsonHttp.Response response = client(central).get("/admin/v1/routes");
        assertThat(response.status()).as(response.text()).isEqualTo(200);
    }

    @Test
    void aClientWithoutCertificateCannotConnect() {
        assertThatThrownBy(() -> client(null).get("/admin/v1/routes")).isInstanceOf(java.io.UncheckedIOException.class);
    }

    @Test
    void aTrustedButUnlistedCertificateIsRefused() {
        JsonHttp.Response response = client(intruder).get("/admin/v1/routes");
        assertThat(response.status()).isEqualTo(403);
        assertThat(response.json().get("code").stringValue()).isEqualTo("CLIENT_NOT_ALLOWED");
    }
}
