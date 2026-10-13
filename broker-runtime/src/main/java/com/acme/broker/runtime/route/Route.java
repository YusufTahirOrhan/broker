package com.acme.broker.runtime.route;

import java.util.Set;

import com.acme.broker.core.route.HttpMethodName;
import com.acme.broker.core.route.PathTemplate;
import com.acme.broker.core.route.RouteDefinition;

/**
 * A route ready to serve: parsed once when the snapshot is loaded. {@code maintask} and {@code subtask} are the
 * registered spelling; metrics and log events use it.
 *
 * @param profile             null when the backend response is returned as is
 * @param skipPermissionCheck only for local override routes, when the developer asked for it
 */
public record Route(String maintask, String subtask, int version, HttpMethodName method, PathTemplate pathTemplate,
                    Set<String> permissions, boolean anonymous, CompiledProfile profile, RouteDefinition definition,
                    String baseUrl, boolean skipPermissionCheck) {

    public Route withBaseUrl(String url) {
        return new Route(maintask, subtask, version, method, pathTemplate, permissions, anonymous, profile, definition,
                url, skipPermissionCheck);
    }

    /** Any-of: one of the route's permissions is enough. */
    public boolean allows(Set<String> userPermissions) {
        if (anonymous || skipPermissionCheck) {
            return true;
        }
        for (String permission : permissions) {
            if (userPermissions.contains(permission)) {
                return true;
            }
        }
        return false;
    }
}
