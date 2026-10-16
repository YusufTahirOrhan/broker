package com.acme.broker.login.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The Koordinatör (the permission project): one base URL and a fixed credential header. Its real API is still to come;
 * paths and field names are settings so that only these values change when it does.
 *
 * @param mode             {@code http}, or {@code static} on a developer's machine (only in the {@code local} profile):
 *                         every user gets {@code staticPermissions}, the projects are {@code staticProjects}
 * @param authHeader       name of the credential header; nothing is sent while {@code authValue} is empty
 * @param permissionsPath  a user's permissions, with {@code {uid}}; answers {@code {"yetkiler": [...]}}
 * @param projectsPath     every project; answers {@code [{"id", "code", "name"}]}
 */
@ConfigurationProperties("koordinator")
public record KoordinatorProperties(String mode, String url, String authHeader, String authValue,
                                    String permissionsPath, String permissionsField, String projectsPath,
                                    String projectCodeField, Duration timeout, List<String> staticPermissions,
                                    List<String> staticProjects) {

    public KoordinatorProperties {
        mode = mode == null ? "http" : mode.toLowerCase(java.util.Locale.ROOT);
        staticPermissions = staticPermissions == null ? List.of() : List.copyOf(staticPermissions);
        staticProjects = staticProjects == null ? List.of() : List.copyOf(staticProjects);
        url = url == null ? "" : url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        authHeader = authHeader == null ? "Authorization" : authHeader;
        permissionsPath = permissionsPath == null ? "/users/{uid}/permissions" : permissionsPath;
        permissionsField = permissionsField == null ? "yetkiler" : permissionsField;
        projectsPath = projectsPath == null ? "/projects" : projectsPath;
        projectCodeField = projectCodeField == null ? "code" : projectCodeField;
        timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
    }
}
