package com.acme.yapi.login.koordinator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.acme.yapi.core.testing.FakeHttpServer;
import com.acme.yapi.login.config.KoordinatorProperties;

@ExtendWith(OutputCaptureExtension.class)
class HttpKoordinatorClientTest {

    static final FakeHttpServer koordinator = FakeHttpServer.start();

    @AfterAll
    static void stop() {
        koordinator.close();
    }

    private static HttpKoordinatorClient client() {
        return new HttpKoordinatorClient(new KoordinatorProperties("http", koordinator.baseUrl(), null, null, null,
                null, null, null, null, null, null));
    }

    @Test
    void aSuccessfulAnswerWithoutAPermissionListIsAFailure(CapturedOutput output) {
        for (String body : List.of("{\"ad\": \"Ali\"}", "{\"yetkiler\": \"ORDER_READ\"}", "{\"yetkiler\": null}",
                "{\"yetkiler\": {\"ORDER_READ\": true}}", "[\"ORDER_READ\"]", "not json", "")) {
            koordinator.json("GET", "/users/ali/permissions", 200, body);

            assertThat(client().permissions("ali")).as(body).isEmpty();
        }
        assertThat(output.getAll().lines().filter(line -> line.contains("WARN") && line.contains("uid=ali")))
                .hasSize(7);
    }

    @Test
    void anEmptyListIsAnAnswer() {
        koordinator.json("GET", "/users/ali/permissions", 200, "{\"yetkiler\": []}");
        assertThat(client().permissions("ali")).contains(List.of());

        koordinator.json("GET", "/users/ali/permissions", 200, "{\"yetkiler\": [\"ORDER_READ\", 7, \"SORGU_TEMEL\"]}");
        assertThat(client().permissions("ali")).contains(List.of("ORDER_READ", "SORGU_TEMEL"));
    }
}
