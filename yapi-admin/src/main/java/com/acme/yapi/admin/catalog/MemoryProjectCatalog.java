package com.acme.yapi.admin.catalog;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Local development and tests: projects from {@code yapi.admin.catalog.projects} (ID → name). */
public class MemoryProjectCatalog implements ProjectCatalog {

    private final Map<String, String> projects;

    public MemoryProjectCatalog(Map<String, String> projects) {
        this.projects = new ConcurrentHashMap<>(projects);
    }

    @Override
    public List<CatalogProject> search(String query) {
        String wanted = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return projects.entrySet().stream()
                .filter(entry -> entry.getValue().toLowerCase(Locale.ROOT).contains(wanted)
                        || entry.getKey().toLowerCase(Locale.ROOT).contains(wanted))
                .map(entry -> new CatalogProject(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(CatalogProject::name))
                .toList();
    }

    @Override
    public Optional<CatalogProject> find(String id) {
        String name = projects.get(id);
        return name == null ? Optional.empty() : Optional.of(new CatalogProject(id, name));
    }

    public void put(String id, String name) {
        projects.put(id, name);
    }

    public void remove(String id) {
        projects.remove(id);
    }
}
