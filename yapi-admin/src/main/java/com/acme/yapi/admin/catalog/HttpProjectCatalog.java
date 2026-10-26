package com.acme.yapi.admin.catalog;

import java.util.List;
import java.util.Optional;

import com.acme.yapi.admin.koordinator.KoordinatorProjects;
import com.acme.yapi.core.naming.Names;

/**
 * Projects from one environment's Koordinatör ({@code yapi.admin.catalog.environment}, PROD by default): its Yapi
 * projects, the ID being {@code projeId} and the name {@code projeAdi}. A search matches either, without case.
 */
public class HttpProjectCatalog implements ProjectCatalog {

    private final KoordinatorProjects koordinator;
    private final String environment;

    public HttpProjectCatalog(KoordinatorProjects koordinator, String environment) {
        this.koordinator = koordinator;
        this.environment = environment;
    }

    @Override
    public List<CatalogProject> search(String query) {
        String wanted = query == null ? "" : Names.key(query.trim());
        return koordinator.projects(environment).stream()
                .filter(project -> Names.key(project.name()).contains(wanted) || Names.key(project.id()).contains(wanted))
                .map(project -> new CatalogProject(project.id(), project.name()))
                .toList();
    }

    @Override
    public Optional<CatalogProject> find(String id) {
        return koordinator.projects(environment).stream()
                .filter(project -> project.id().equals(id))
                .map(project -> new CatalogProject(project.id(), project.name()))
                .findFirst();
    }
}
