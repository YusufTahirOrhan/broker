package com.acme.yapi.login.koordinator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.acme.yapi.core.testing.FakeHttpServer;
import com.acme.yapi.login.config.KoordinatorProperties;

/** The Koordinatör's real answers (2026-10-21): a user's active permissions and the projects with their flag. */
@ExtendWith(OutputCaptureExtension.class)
class HttpKoordinatorClientTest {

    static final FakeHttpServer koordinator = FakeHttpServer.start();
    static final String PERMISSIONS = "/api/koordinator/personel-yetki/aktif-yetkiler/12345";
    static final String PROJECTS = "/api/koordinator/projeler-ve-yetkileri";

    @AfterAll
    static void stop() {
        koordinator.close();
    }

    @BeforeEach
    void reset() {
        koordinator.reset();
    }

    private static HttpKoordinatorClient client() {
        return new HttpKoordinatorClient(new KoordinatorProperties("http", koordinator.baseUrl(), null, null, null,
                null, null, null, null, null, null, null, null));
    }

    private static String permission(String name, String suspended) {
        return """
                {"id": 1, "projeId": 7, "projeIsim": "Sipariş", "yetkiIsim": "%s", "yetkiAciklama": "x",
                 "baslangicTarihi": "2026-01-01", "bitisTarihi": null, "askidaMi": %s, "yetkiTalepDurum": "ONAY",
                 "yetkiId": 3}""".formatted(name, suspended);
    }

    @Test
    void everyListsActivePermissionsInOrderWithoutRepeats() {
        koordinator.json("GET", PERMISSIONS, 200, "{\"additionalProp1\": [%s, %s, %s], \"additionalProp2\": [%s, %s, %s, %s]}"
                .formatted(permission("ORDER_READ", "false"), permission("ORDER_WRITE", "true"),
                        permission("SORGU_TEMEL", "null"),
                        permission("CRM_AGENT", "0"), permission("ORDER_READ", "false"),
                        permission("CRM_ADMIN", "1"), permission("LOGPROJECT_TEMEL", "\"1\"")));

        assertThat(client().permissions("12345")).contains(List.of("ORDER_READ", "SORGU_TEMEL", "CRM_AGENT"));
    }

    @Test
    void anEmptyMapIsAUserWithoutPermissions() {
        koordinator.json("GET", PERMISSIONS, 200, "{}");
        assertThat(client().permissions("12345")).contains(List.of());

        koordinator.json("GET", PERMISSIONS, 200, "{\"additionalProp1\": []}");
        assertThat(client().permissions("12345")).contains(List.of());
    }

    @Test
    void anyOtherAnswerIsTheKoordinatorsFailure(CapturedOutput output) {
        List<String> bodies = List.of("[]", "{\"additionalProp1\": {}}", "{\"additionalProp1\": [\"ORDER_READ\"]}",
                "{\"additionalProp1\": [{\"yetkiAciklama\": \"isimsiz\"}]}", "{\"yetkiler\": \"ORDER_READ\"}",
                "not json", "");
        for (String body : bodies) {
            koordinator.json("GET", PERMISSIONS, 200, body);

            assertThat(client().permissions("12345")).as(body).isEmpty();
        }
        koordinator.json("GET", PERMISSIONS, 500, "{}");
        assertThat(client().permissions("12345")).isEmpty();

        assertThat(output.getAll().lines().filter(line -> line.contains("WARN") && line.contains("uid=12345")))
                .hasSize(bodies.size() + 1);
    }

    @Test
    void applicationsAreTheYapiProjectsNames() throws IOException {
        koordinator.json("GET", PROJECTS, 200, """
                [{"projeId": 1, "projeAdi": "web", "is_yapi": true, "yetkiList": [], "projeDurum": "AKTIF"},
                 {"projeId": 2, "projeAdi": "Mobil", "is_yapi": 1, "yetkiList": []},
                 {"projeId": 3, "projeAdi": "muhasebe", "is_yapi": false, "yetkiList": []},
                 {"projeId": 4, "projeAdi": "eski", "yetkiList": []},
                 {"projeId": 5, "is_yapi": true},
                 {"projeId": 6, "projeAdi": "web", "is_yapi": true}]
                """);

        assertThat(client().applications()).containsExactly("web", "Mobil");
    }

    @Test
    void aProjectListThatIsNotAnArrayFails() {
        koordinator.json("GET", PROJECTS, 200, "{\"projeler\": []}");
        assertThatThrownBy(() -> client().applications()).isInstanceOf(IOException.class);

        koordinator.json("GET", PROJECTS, 503, "[]");
        assertThatThrownBy(() -> client().applications()).hasMessageContaining("503");
    }
}
