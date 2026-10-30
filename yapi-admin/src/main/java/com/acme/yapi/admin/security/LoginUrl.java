package com.acme.yapi.admin.security;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Where the screen sends a user without a session: the login application's page, written into every 401 as
 * {@code loginUrl} ({@code yapi.admin.login-url}); left out while it is not set.
 */
@Component
public class LoginUrl {

    private final String url;

    public LoginUrl(@Value("${yapi.admin.login-url:}") String url) {
        this.url = url == null || url.isBlank() ? null : url;
    }

    /** Extra fields of a 401 problem. */
    public Map<String, Object> properties() {
        return url == null ? Map.of() : Map.of("loginUrl", url);
    }
}
