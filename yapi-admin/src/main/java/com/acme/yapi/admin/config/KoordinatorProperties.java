package com.acme.yapi.admin.config;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The Koordinatör (the permission project) of each environment. The project catalog, a project's permissions and
 * whether a permission exists in an environment all come from {@code projectsPath} of that environment's Koordinatör.
 * Paths and field names are settings; the defaults are its real API.
 *
 * @param urls                each environment's Koordinatör (DEV, PREPROD, PROD)
 * @param authHeader          name of the credential header; nothing is sent while {@code authValue} is empty
 * @param projectsPath        every project with its permissions: {@code [{"projeId", "projeAdi", "is_yapi",
 *                            "yetkiList": [{"yetki", ...}]}]}
 * @param yapiField           true for the projects that use Yapi; only those are read
 * @param cacheTtl            how long an environment's list is kept
 */
@ConfigurationProperties("koordinator")
public record KoordinatorProperties(Map<String, String> urls, String authHeader, String authValue,
                                    String projectsPath, String projectIdField, String projectNameField,
                                    String yapiField, String permissionsField, String permissionCodeField,
                                    Duration cacheTtl, Duration timeout) {

    public KoordinatorProperties {
        urls = urls == null ? Map.of() : Map.copyOf(urls);
        authHeader = authHeader == null || authHeader.isBlank() ? "Authorization" : authHeader;
        projectsPath = projectsPath == null ? "/api/koordinator/projeler-ve-yetkileri" : projectsPath;
        projectIdField = projectIdField == null ? "projeId" : projectIdField;
        projectNameField = projectNameField == null ? "projeAdi" : projectNameField;
        yapiField = yapiField == null ? "is_yapi" : yapiField;
        permissionsField = permissionsField == null ? "yetkiList" : permissionsField;
        permissionCodeField = permissionCodeField == null ? "yetki" : permissionCodeField;
        cacheTtl = cacheTtl == null ? Duration.ofMinutes(5) : cacheTtl;
        timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
    }

    /** Adds the credential header to a request to the Koordinatör. */
    public HttpRequest.Builder authorize(HttpRequest.Builder request) {
        return authValue == null || authValue.isEmpty() ? request : request.header(authHeader, authValue);
    }
}
