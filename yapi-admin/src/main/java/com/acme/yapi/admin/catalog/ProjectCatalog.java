package com.acme.yapi.admin.catalog;

import java.util.List;
import java.util.Optional;

/** The company's project service: the source of project IDs and display names. */
public interface ProjectCatalog {

    record CatalogProject(String id, String name) {
    }

    List<CatalogProject> search(String query);

    Optional<CatalogProject> find(String id);
}
