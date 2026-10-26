package com.acme.yapi.login.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.acme.yapi.core.context.UserContextToken;
import com.acme.yapi.core.json.Json;
import com.acme.yapi.login.config.LoginProperties;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.node.ObjectNode;

/**
 * Every call must carry the broker's signature ({@code X-User-Context}, issued within
 * {@code login.user-context-max-age}), except the actuator's and the internal endpoints ({@code /actuator/},
 * {@code /internal/}; the Koordinatör's logout has its own secret); anything else gets 403, so new endpoints are
 * protected too. The path is the container's decoded and normalized one, without path parameters: {@code /login;x=1}
 * and {@code /%6Cogin}, which Spring MVC maps to login, are checked as {@code /login}.
 *
 * <p>Without {@code login.user-context-secret} nothing is checked; only the {@code local} profile may run so, any
 * other profile does not start.
 */
@Component
public class UserContextFilter extends OncePerRequestFilter {

    static final String HEADER = "X-User-Context";
    private static final Logger log = LoggerFactory.getLogger(UserContextFilter.class);
    private static final List<String> UNSIGNED_PREFIXES = List.of("/actuator/", "/internal/");

    private final byte[] secret;
    private final LoginProperties properties;

    public UserContextFilter(LoginProperties properties, Environment environment) {
        this.properties = properties;
        String configured = properties.userContextSecret();
        if (configured == null || configured.isEmpty()) {
            if (!Arrays.asList(environment.getActiveProfiles()).contains("local")) {
                throw new IllegalStateException("login.user-context-secret boş; broker'ın imzası yalnızca local "
                        + "profilinde kontrol edilmeden çalışılabilir");
            }
            log.warn("login.user-context-secret boş: imza kontrolü kapalı (local profili)");
            this.secret = null;
        } else {
            this.secret = configured.getBytes(StandardCharsets.UTF_8);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (secret == null) {
            return true;
        }
        String path = path(request);
        return UNSIGNED_PREFIXES.stream().anyMatch(path::startsWith);
    }

    /** The path the servlet was mapped by: decoded, normalized, path parameters removed. */
    private static String path(HttpServletRequest request) {
        String pathInfo = request.getPathInfo();
        return pathInfo == null ? request.getServletPath() : request.getServletPath() + pathInfo;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (UserContextToken.verify(secret, request.getHeader(HEADER), Instant.now(), properties.userContextMaxAge())
                .isPresent()) {
            chain.doFilter(request, response);
            return;
        }
        log.warn("İmzasız ya da geçersiz imzalı çağrı reddedildi: {} {}", request.getMethod(), request.getRequestURI());
        ObjectNode body = Json.object();
        body.put("response", false);
        body.put("status", 403);
        body.put("message", "Çağrı broker'ın imzasını taşımıyor.");
        response.setStatus(403);
        response.setContentType("application/json");
        response.getOutputStream().write(Json.writeBytes(body));
    }
}
