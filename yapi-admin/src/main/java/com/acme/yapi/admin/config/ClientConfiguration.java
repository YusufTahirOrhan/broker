package com.acme.yapi.admin.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.acme.yapi.admin.authz.AuthorizationService;
import com.acme.yapi.admin.authz.HttpAuthorizationService;
import com.acme.yapi.admin.authz.MemoryAuthorizationService;
import com.acme.yapi.admin.catalog.HttpProjectCatalog;
import com.acme.yapi.admin.catalog.MemoryProjectCatalog;
import com.acme.yapi.admin.catalog.ProjectCatalog;

/** Projects and permissions: from the Koordinatör over HTTP in environments, in memory locally. */
@Configuration(proxyBeanMethods = false)
public class ClientConfiguration {

    @Bean
    ProjectCatalog projectCatalog(AdminProperties properties, KoordinatorProperties koordinator) {
        AdminProperties.Catalog catalog = properties.catalog();
        if ("memory".equals(catalog.mode())) {
            return new MemoryProjectCatalog(catalog.projects());
        }
        String url = koordinator.urlOr(catalog.url());
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("koordinator.url zorunlu (ya da yapi.admin.catalog.mode: memory)");
        }
        return new HttpProjectCatalog(url, koordinator);
    }

    @Bean
    AuthorizationService authorizationService(AdminProperties properties, KoordinatorProperties koordinator) {
        AdminProperties.Authorization authorization = properties.authorization();
        return "memory".equals(authorization.mode())
                ? new MemoryAuthorizationService(authorization.permissions())
                : new HttpAuthorizationService(authorization.urls(), koordinator);
    }
}
