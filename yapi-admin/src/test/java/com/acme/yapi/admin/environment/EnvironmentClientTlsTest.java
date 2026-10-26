package com.acme.yapi.admin.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import javax.net.ssl.SSLParameters;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslStoreBundle;

import com.acme.yapi.admin.config.AdminProperties;
import com.acme.yapi.admin.web.ApiException;
import com.acme.yapi.core.testing.FakeHttpServer;
import com.acme.yapi.core.testing.TestCertificates;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

/** The Admin API client presents the bundle's client certificate to a server that requires one. */
class EnvironmentClientTlsTest {

    static final TestCertificates certificates = TestCertificates.create();
    static final Path server = certificates.keyPair("server", "CN=localhost", "dns:localhost,ip:127.0.0.1");
    static final Path central = certificates.keyPair("central", "CN=yapi-admin", null);
    static final Path serverTrust = certificates.trustStore("server-trust", central);
    static final Path clientTrust = certificates.trustStore("client-trust", server);
    static HttpsServer https;

    @BeforeAll
    static void start() throws Exception {
        https = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        https.setHttpsConfigurator(new HttpsConfigurator(TestCertificates.context(server, serverTrust)) {
            @Override
            public void configure(HttpsParameters parameters) {
                SSLParameters ssl = getSSLContext().getDefaultSSLParameters();
                ssl.setNeedClientAuth(true);
                parameters.setSSLParameters(ssl);
            }
        });
        https.createContext("/admin/v1/routes", exchange -> FakeHttpServer.respond(exchange, 200,
                Map.of("Content-Type", "application/json"), "{\"configVersion\": 7}".getBytes(StandardCharsets.UTF_8)));
        https.start();
    }

    @AfterAll
    static void stop() {
        https.stop(0);
    }

    private static Environment dev() {
        return new Environment("DEV", "Geliştirme", 1, "https://localhost:" + https.getAddress().getPort(), false, true);
    }

    private static EnvironmentClient client(String bundle) throws Exception {
        DefaultSslBundleRegistry registry = new DefaultSslBundleRegistry();
        registry.registerBundle("env-client", SslBundle.of(SslStoreBundle.of(TestCertificates.load(central),
                TestCertificates.PASSWORD, TestCertificates.load(clientTrust))));
        AdminProperties properties = new AdminProperties(null, null, null,
                new AdminProperties.Environments(bundle, null, null, null), null, null, null);
        return new EnvironmentClient(properties,
                new StaticListableBeanFactory(Map.of("sslBundles", registry)).getBeanProvider(SslBundles.class));
    }

    @Test
    void theClientCertificateOpensTheConnection() throws Exception {
        assertThat(client("env-client").call(dev(), "GET", "/admin/v1/routes", null).get("configVersion").intValue())
                .isEqualTo(7);
    }

    @Test
    void withoutTheBundleTheEnvironmentIsUnreachable() throws Exception {
        assertThatThrownBy(() -> client(null).call(dev(), "GET", "/admin/v1/routes", null))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo("ENV_UNREACHABLE"));
    }
}
