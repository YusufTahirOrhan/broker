package com.acme.yapi.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.acme.yapi.admin.authz.AuthorizationService;
import com.acme.yapi.admin.authz.HttpAuthorizationService;
import com.acme.yapi.admin.catalog.HttpProjectCatalog;
import com.acme.yapi.admin.catalog.ProjectCatalog;
import com.acme.yapi.admin.config.KoordinatorProperties;
import com.acme.yapi.core.testing.FakeHttpServer;

/** Projects and every environment's permissions come from the Koordinatör's one base URL, with its credential. */
class KoordinatorClientsTest {

    private final FakeHttpServer koordinator = FakeHttpServer.start();
    private final KoordinatorProperties settings = new KoordinatorProperties(koordinator.baseUrl(), "X-Api-Key", "k1");

    @AfterEach
    void stop() {
        koordinator.close();
    }

    @Test
    void projectsComeFromTheKoordinatorWithItsCredential() {
        koordinator.json("GET", "/projects", 200, "[{\"id\": \"P-1\", \"code\": \"web\", \"name\": \"Web Portalı\"}]");

        ProjectCatalog catalog = new HttpProjectCatalog(settings.urlOr(""), settings);

        assertThat(catalog.search(null)).containsExactly(new ProjectCatalog.CatalogProject("P-1", "Web Portalı"));
        assertThat(koordinator.requests("/projects").getFirst().header("X-Api-Key")).isEqualTo("k1");
    }

    @Test
    void everyEnvironmentAsksTheKoordinatorUnlessItHasItsOwnUrl() {
        koordinator.json("GET", "/permissions", 200, "[{\"code\": \"ORDER_READ\", \"description\": \"Okuma\"}]");
        FakeHttpServer own = FakeHttpServer.start();
        own.json("GET", "/permissions", 200, "[]");
        try {
            AuthorizationService permissions = new HttpAuthorizationService(Map.of("PREPROD", "", "PROD", own.baseUrl()),
                    settings);

            assertThat(permissions.list("DEV", "ORDER")).extracting(AuthorizationService.Permission::code)
                    .containsExactly("ORDER_READ");
            assertThat(permissions.list("PREPROD", "ORDER")).hasSize(1);
            assertThat(permissions.list("PROD", "ORDER")).isEmpty();
            assertThat(koordinator.requests("/permissions")).hasSize(2)
                    .allSatisfy(request -> assertThat(request.header("X-Api-Key")).isEqualTo("k1"));
        } finally {
            own.close();
        }
    }

    @Test
    void withoutACredentialNoHeaderIsSent() {
        koordinator.json("GET", "/projects", 200, "[]");

        new HttpProjectCatalog(koordinator.baseUrl(), new KoordinatorProperties(koordinator.baseUrl(), null, ""))
                .search(null);

        assertThat(koordinator.requests("/projects").getFirst().header("Authorization")).isNull();
    }
}
