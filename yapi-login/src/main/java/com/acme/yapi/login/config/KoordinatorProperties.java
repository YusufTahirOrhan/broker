package com.acme.yapi.login.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The Koordinatör (the permission project) of this environment: one base URL and an optional credential header.
 * Paths and field names are settings; the defaults are its real API.
 *
 * @param mode             {@code http}, or {@code static} on a developer's machine (only in the {@code local} profile):
 *                         every user gets {@code staticPermissions}, the applications are {@code staticProjects}
 * @param authHeader       name of the credential header; nothing is sent while {@code authValue} is empty
 * @param permissionsPath  a user's active permissions, with {@code {uid}} (the sicil); answers a map whose every value
 *                         is a list of {@code {"yetkiIsim", "askidaMi", ...}}
 * @param permissionField  the permission's name in those lists
 * @param suspendedField   true (or 1) when the permission is suspended, i.e. not active
 * @param projectsPath     every project with its permissions; answers {@code [{"projeId", "projeAdi", "is_yapi", ...}]}
 * @param applicationField a project's application name ({@code application} in the broker's requests)
 * @param yapiField        true for the projects that use Yapi; only those are applications
 */
@ConfigurationProperties("koordinator")
public record KoordinatorProperties(String mode, String url, String authHeader, String authValue,
                                    String permissionsPath, String permissionField, String suspendedField,
                                    String projectsPath, String applicationField, String yapiField, Duration timeout,
                                    List<String> staticPermissions, List<String> staticProjects) {

    public KoordinatorProperties {
        mode = mode == null ? "http" : mode.toLowerCase(java.util.Locale.ROOT);
        staticPermissions = staticPermissions == null ? List.of() : List.copyOf(staticPermissions);
        staticProjects = staticProjects == null ? List.of() : List.copyOf(staticProjects);
        url = url == null ? "" : url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        authHeader = authHeader == null ? "Authorization" : authHeader;
        permissionsPath = permissionsPath == null ? "/api/koordinator/personel-yetki/aktif-yetkiler/{uid}"
                : permissionsPath;
        permissionField = permissionField == null ? "yetkiIsim" : permissionField;
        suspendedField = suspendedField == null ? "askidaMi" : suspendedField;
        projectsPath = projectsPath == null ? "/api/koordinator/projeler-ve-yetkileri" : projectsPath;
        applicationField = applicationField == null ? "projeAdi" : applicationField;
        yapiField = yapiField == null ? "is_yapi" : yapiField;
        timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
    }
}
