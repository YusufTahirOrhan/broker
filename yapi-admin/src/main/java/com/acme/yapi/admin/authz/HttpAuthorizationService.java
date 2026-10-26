package com.acme.yapi.admin.authz;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.acme.yapi.admin.koordinator.KoordinatorProjects;

/**
 * Permissions from each environment's Koordinatör: a project's are the {@code yetkiList} of its Koordinatör project
 * (matched by {@code projeId}; an unlinked project has none), and a permission exists when any Yapi project lists it.
 * The Koordinatör gives no description.
 */
public class HttpAuthorizationService implements AuthorizationService {

    private final KoordinatorProjects koordinator;

    public HttpAuthorizationService(KoordinatorProjects koordinator) {
        this.koordinator = koordinator;
    }

    @Override
    public List<Permission> list(String environment, String externalProjectId, String prefix) {
        if (externalProjectId == null) {
            return List.of();
        }
        return koordinator.projects(environment).stream()
                .filter(project -> project.id().equals(externalProjectId))
                .findFirst()
                .map(project -> project.permissions().stream().map(code -> new Permission(code, null)).toList())
                .orElse(List.of());
    }

    @Override
    public Set<String> existing(String environment, Collection<String> codes) {
        Set<String> all = new LinkedHashSet<>();
        koordinator.projects(environment).forEach(project -> all.addAll(project.permissions()));
        Set<String> found = new LinkedHashSet<>();
        codes.stream().filter(all::contains).forEach(found::add);
        return found;
    }
}
