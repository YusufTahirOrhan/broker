package com.acme.yapi.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.acme.yapi.admin.authz.AuthorizationService;
import com.acme.yapi.admin.authz.HttpAuthorizationService;
import com.acme.yapi.admin.catalog.HttpProjectCatalog;
import com.acme.yapi.admin.catalog.ProjectCatalog;
import com.acme.yapi.admin.config.KoordinatorProperties;
import com.acme.yapi.admin.koordinator.KoordinatorProjects;
import com.acme.yapi.admin.web.ApiException;
import com.acme.yapi.core.testing.FakeHttpServer;

/**
 * The catalog, a project's permissions and a permission's existence all come from {@code projeler-ve-yetkileri} of
 * the environment's own Koordinatör: only Yapi projects, kept a few minutes per environment.
 */
class KoordinatorClientsTest {

    static final String PATH = "/api/koordinator/projeler-ve-yetkileri";
    static final String PROD_PROJECTS = """
            [{"projeId": 7, "projeAdi": "Sipariş Servisi", "is_yapi": true, "projeDurum": "AKTIF",
              "yetkiList": [{"id": 1, "yetki": "ORDER_READ", "ozelYetki": false, "durum": "AKTIF"},
                            {"id": 2, "yetki": "ORDER_WRITE", "ozelYetki": false, "durum": "AKTIF"}]},
             {"projeId": 9, "projeAdi": "CRM", "is_yapi": true, "yetkiList": [{"id": 3, "yetki": "CRM_AGENT"}]},
             {"projeId": 11, "projeAdi": "Muhasebe", "is_yapi": false, "yetkiList": [{"id": 4, "yetki": "MUH_OKU"}]}]
            """;

    private final FakeHttpServer dev = FakeHttpServer.start();
    private final FakeHttpServer prod = FakeHttpServer.start();
    private Instant now = Instant.parse("2026-10-26T09:00:00Z");

    @AfterEach
    void stop() {
        dev.close();
        prod.close();
    }

    private KoordinatorProjects projects(String authValue) {
        KoordinatorProperties settings = new KoordinatorProperties(Map.of("DEV", dev.baseUrl(), "PROD", prod.baseUrl()),
                "X-Api-Key", authValue, null, null, null, null, null, null, Duration.ofMinutes(5), null);
        Clock clock = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now;
            }
        };
        return new KoordinatorProjects(settings, clock);
    }

    @Test
    void theCatalogIsTheYapiProjectsOfItsEnvironment() {
        prod.json("GET", PATH, 200, PROD_PROJECTS);
        ProjectCatalog catalog = new HttpProjectCatalog(projects("k1"), "PROD");

        assertThat(catalog.search(null)).containsExactly(new ProjectCatalog.CatalogProject("7", "Sipariş Servisi"),
                new ProjectCatalog.CatalogProject("9", "CRM"));
        assertThat(catalog.search("SİPARİŞ".toLowerCase(java.util.Locale.ROOT))).isEmpty();
        assertThat(catalog.search("sipariş")).extracting(ProjectCatalog.CatalogProject::id).containsExactly("7");
        assertThat(catalog.search("crm")).extracting(ProjectCatalog.CatalogProject::id).containsExactly("9");
        assertThat(catalog.find("9")).contains(new ProjectCatalog.CatalogProject("9", "CRM"));
        assertThat(catalog.find("11")).isEmpty();
        assertThat(prod.requests(PATH)).hasSize(1)
                .allSatisfy(request -> assertThat(request.header("X-Api-Key")).isEqualTo("k1"));
        assertThat(dev.requests()).isEmpty();
    }

    @Test
    void permissionsAreTheProjectsListInEachEnvironment() {
        prod.json("GET", PATH, 200, PROD_PROJECTS);
        dev.json("GET", PATH, 200, """
                [{"projeId": 7, "projeAdi": "Sipariş Servisi", "is_yapi": true,
                  "yetkiList": [{"yetki": "ORDER_READ"}, {"yetki": "ORDER_EXPORT"}]}]
                """);
        AuthorizationService permissions = new HttpAuthorizationService(projects(""));

        assertThat(permissions.list("PROD", "7", "ORDER")).containsExactly(
                new AuthorizationService.Permission("ORDER_READ", null),
                new AuthorizationService.Permission("ORDER_WRITE", null));
        assertThat(permissions.list("DEV", "7", "ORDER")).extracting(AuthorizationService.Permission::code)
                .containsExactly("ORDER_READ", "ORDER_EXPORT");
        assertThat(permissions.list("PROD", "11", "MUH")).isEmpty();
        assertThat(permissions.list("PROD", null, "ORDER")).isEmpty();

        assertThat(permissions.existing("PROD", List.of("ORDER_WRITE", "CRM_AGENT", "ORDER_EXPORT", "MUH_OKU")))
                .isEqualTo(Set.of("ORDER_WRITE", "CRM_AGENT"));
        assertThat(permissions.existing("DEV", List.of("ORDER_WRITE", "ORDER_EXPORT"))).containsExactly("ORDER_EXPORT");
        assertThat(prod.requests(PATH).getFirst().header("X-Api-Key")).isNull();
    }

    @Test
    void anEnvironmentsListIsKeptForTheCacheTime() {
        prod.json("GET", PATH, 200, PROD_PROJECTS);
        KoordinatorProjects projects = projects("");

        projects.projects("PROD");
        now = now.plus(Duration.ofMinutes(4));
        projects.projects("PROD");
        assertThat(prod.requests(PATH)).hasSize(1);

        now = now.plus(Duration.ofMinutes(2));
        prod.json("GET", PATH, 200, "[]");
        assertThat(projects.projects("PROD")).isEmpty();
        assertThat(prod.requests(PATH)).hasSize(2);
    }

    @Test
    void failuresAreUpstreamErrorsAndAreNotKept() {
        KoordinatorProjects projects = projects("");
        prod.json("GET", PATH, 500, "{}");
        assertThatThrownBy(() -> projects.projects("PROD")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo("UPSTREAM_ERROR"));
        prod.json("GET", PATH, 200, "{\"projeler\": []}");
        assertThatThrownBy(() -> projects.projects("PROD")).hasMessageContaining("dizisi değil");
        assertThatThrownBy(() -> projects.projects("PREPROD")).hasMessageContaining("koordinator.urls.PREPROD");

        prod.json("GET", PATH, 200, PROD_PROJECTS);
        assertThat(projects.projects("PROD")).hasSize(2);
    }
}
