package com.acme.yapi.runtime.route;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.acme.yapi.core.naming.Names;
import com.acme.yapi.core.plugin.Plugin;
import com.acme.yapi.core.plugin.Plugins;
import com.acme.yapi.core.route.AlarmRoute;

/**
 * An immutable view of the runtime DB at one config version. Lookups ignore case with {@link Names#key}, so
 * {@code GET_ID} finds {@code get_id} under a Turkish default locale too.
 */
public final class RouteSnapshot {

    public static final RouteSnapshot EMPTY = new RouteSnapshot(-1, Map.of(), Map.of(), Map.of(), List.of());

    /**
     * A project of the environment; disabled projects are known but serve no routes.
     *
     * @param alarmRoute       the alarm service route of its subtasks that inherit one; null when none
     * @param permissionPrefix the project's permission prefix; null when the central admin has not sent it
     */
    public record Project(String maintask, String baseUrl, boolean enabled, AlarmRoute alarmRoute,
                          String permissionPrefix) {
    }

    private final long version;
    private final Map<String, Route> routes;
    private final Map<String, Project> projects;
    private final Map<String, CompiledProfile> profiles;
    private final List<Plugin> plugins;

    private RouteSnapshot(long version, Map<String, Route> routes, Map<String, Project> projects,
                          Map<String, CompiledProfile> profiles, List<Plugin> plugins) {
        this.version = version;
        this.routes = Map.copyOf(routes);
        this.projects = Map.copyOf(projects);
        this.profiles = Map.copyOf(profiles);
        this.plugins = List.copyOf(plugins);
    }

    public static RouteSnapshot of(long version, Collection<Route> routes, Collection<Project> projects,
                                   Collection<CompiledProfile> profiles) {
        return of(version, routes, projects, profiles, List.of());
    }

    public static RouteSnapshot of(long version, Collection<Route> routes, Collection<Project> projects,
                                   Collection<CompiledProfile> profiles, Collection<Plugin> plugins) {
        Map<String, Route> byKey = new HashMap<>();
        routes.forEach(route -> byKey.put(key(route.maintask(), route.subtask()), route));
        Map<String, Project> byProject = new HashMap<>();
        projects.forEach(project -> byProject.put(Names.key(project.maintask()), project));
        Map<String, CompiledProfile> byName = new HashMap<>();
        profiles.forEach(profile -> byName.put(profile.name(), profile));
        return new RouteSnapshot(version, byKey, byProject, byName, List.copyOf(plugins));
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

    /** Every plugin the environment serves; {@link com.acme.yapi.core.plugin.Plugins#select} picks a route's. */
    public List<Plugin> plugins() {
        return plugins;
    }

    /** The plugins of a route, in the order they apply. */
    public List<Plugin> pluginsOf(Route route) {
        return Plugins.select(plugins, route.maintask(), route.definition());
    }

    public int size() {
        return routes.size();
    }
}
