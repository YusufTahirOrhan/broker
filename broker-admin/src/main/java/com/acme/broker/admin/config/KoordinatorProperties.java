package com.acme.broker.admin.config;

import java.net.http.HttpRequest;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The Koordinatör (the permission project) that projects and permissions come from: one base URL for the project
 * catalog and every environment's permissions, and a fixed credential header. {@code broker.admin.catalog.url} and
 * {@code broker.admin.authorization.urls.<ENV>} override the URL when set. The giriş service uses the same settings.
 *
 * @param authHeader name of the credential header; nothing is sent while {@code authValue} is empty
 */
@ConfigurationProperties("koordinator")
public record KoordinatorProperties(String url, String authHeader, String authValue) {

    public KoordinatorProperties {
        authHeader = authHeader == null || authHeader.isBlank() ? "Authorization" : authHeader;
    }

    /** Adds the credential header to a request to the Koordinatör. */
    public HttpRequest.Builder authorize(HttpRequest.Builder request) {
        return authValue == null || authValue.isEmpty() ? request : request.header(authHeader, authValue);
    }

    /** {@code override} when it is set, else the Koordinatör's URL. */
    public String urlOr(String override) {
        return override != null && !override.isBlank() ? override : url;
    }
}
