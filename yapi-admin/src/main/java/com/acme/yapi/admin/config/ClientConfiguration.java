package com.acme.yapi.admin.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.acme.yapi.admin.authz.AuthorizationService;
import com.acme.yapi.admin.authz.HttpAuthorizationService;
import com.acme.yapi.admin.authz.MemoryAuthorizationService;
import com.acme.yapi.admin.catalog.HttpProjectCatalog;
import com.acme.yapi.admin.catalog.MemoryProjectCatalog;
import com.acme.yapi.admin.catalog.ProjectCatalog;
import com.acme.yapi.admin.koordinator.KoordinatorProjects;

/** Projects and permissions: from each environment's Koordinatör in environments, in memory locally. */
@Configuration(proxyBeanMethods = false)
public class ClientConfiguration {

    @Bean
    KoordinatorProjects koordinatorProjects(KoordinatorProperties koordinator) {
        return new KoordinatorProjects(koordinator, Clock.systemUTC());
    }

    @Bean
    ProjectCatalog projectCatalog(AdminProperties properties, KoordinatorProperties koordinator,
                                  KoordinatorProjects projects) {
        AdminProperties.Catalog catalog = properties.catalog();
        if ("memory".equals(catalog.mode())) {
            return new MemoryProjectCatalog(catalog.projects());
        }
        String url = koordinator.urls().get(catalog.environment());
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("koordinator.urls." + catalog.environment()
                    + " zorunlu (proje kataloğu; ya da yapi.admin.catalog.mode: memory)");
        }
        return new HttpProjectCatalog(projects, catalog.environment());
    }

    @Bean
    AuthorizationService authorizationService(AdminProperties properties, KoordinatorProjects projects) {
        AdminProperties.Authorization authorization = properties.authorization();
        return "memory".equals(authorization.mode())
                ? new MemoryAuthorizationService(authorization.permissions())
                : new HttpAuthorizationService(projects);
    }
}
