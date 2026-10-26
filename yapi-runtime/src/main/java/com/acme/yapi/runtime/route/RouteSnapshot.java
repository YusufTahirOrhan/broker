package com.acme.yapi.runtime.route;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.acme.yapi.core.naming.Names;

/**
 * An immutable view of the runtime DB at one config version. Lookups ignore case with {@link Names#key}, so
 * {@code GET_ID} finds {@code get_id} under a Turkish default locale too.
 */
public final class RouteSnapshot {

    public static final RouteSnapshot EMPTY = new RouteSnapshot(-1, Map.of(), Map.of(), Map.of());

    /** A project of the environment; disabled projects are known but serve no routes. */
    public record Project(String maintask, String baseUrl, boolean enabled) {
    }

    private final long version;
    private final Map<String, Route> routes;
    private final Map<String, Project> projects;
    private final Map<String, CompiledProfile> profiles;

    private RouteSnapshot(long version, Map<String, Route> routes, Map<String, Project> projects,
                          Map<String, CompiledProfile> profiles) {
        this.version = version;
        this.routes = Map.copyOf(routes);
        this.projects = Map.copyOf(projects);
        this.profiles = Map.copyOf(profiles);
    }

    public static RouteSnapshot of(long version, Collection<Route> routes, Collection<Project> projects,
                                   Collection<CompiledProfile> profiles) {
        Map<String, Route> byKey = new HashMap<>();
        routes.forEach(route -> byKey.put(key(route.maintask(), route.subtask()), route));
        Map<String, Project> byProject = new HashMap<>();
        projects.forEach(project -> byProject.put(Names.key(project.maintask()), project));
        Map<String, CompiledProfile> byName = new HashMap<>();
        profiles.forEach(profile -> byName.put(profile.name(), profile));
        return new RouteSnapshot(version, byKey, byProject, byName);
    }

    static String key(String maintask, String subtask) {
        return Names.key(maintask) + '\u0000' + Names.key(subtask);
    }

    public long version() {
        return version;
    }

    public Optional<Route> find(String maintask, String subtask) {
        if (maintask == null || subtask == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(routes.get(key(maintask, subtask)));
    }

    /** Registered spelling of a project, if the environment knows it. */
    public Optional<String> project(String maintask) {
        return projectOf(maintask).map(Project::maintask);
    }

    public Optional<Project> projectOf(String maintask) {
        return maintask == null ? Optional.empty() : Optional.ofNullable(projects.get(Names.key(maintask)));
    }

    public Optional<CompiledProfile> profile(String name) {
        return Optional.ofNullable(profiles.get(name));
    }

    public Collection<Route> routes() {
        return routes.values();
    }

    public Collection<Project> projects() {
        return projects.values();
    }

    public Collection<CompiledProfile> profiles() {
        return profiles.values();
    }

    public int size() {
        return routes.size();
    }
}
