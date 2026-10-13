package com.acme.broker.admin.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.acme.broker.admin.authz.AuthorizationService;
import com.acme.broker.admin.authz.HttpAuthorizationService;
import com.acme.broker.admin.authz.MemoryAuthorizationService;
import com.acme.broker.admin.catalog.HttpProjectCatalog;
import com.acme.broker.admin.catalog.MemoryProjectCatalog;
import com.acme.broker.admin.catalog.ProjectCatalog;

/** The project service and the authorization services: HTTP in environments, in memory locally. */
@Configuration(proxyBeanMethods = false)
public class ClientConfiguration {

    @Bean
    ProjectCatalog projectCatalog(AdminProperties properties) {
        AdminProperties.Catalog catalog = properties.catalog();
        if ("memory".equals(catalog.mode())) {
            return new MemoryProjectCatalog(catalog.projects());
        }
        if (catalog.url() == null || catalog.url().isBlank()) {
            throw new IllegalStateException("broker.admin.catalog.url zorunlu (ya da mode: memory)");
        }
        return new HttpProjectCatalog(catalog.url());
    }

    @Bean
    AuthorizationService authorizationService(AdminProperties properties) {
        AdminProperties.Authorization authorization = properties.authorization();
        return "memory".equals(authorization.mode())
                ? new MemoryAuthorizationService(authorization.permissions())
                : new HttpAuthorizationService(authorization.urls());
    }
}
