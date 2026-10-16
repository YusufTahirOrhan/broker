package com.acme.broker.login.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.acme.broker.core.context.UserContextToken;
import com.acme.broker.core.json.Json;
import com.acme.broker.login.config.LoginProperties;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.node.ObjectNode;

/**
 * When {@code login.user-context-secret} is set, login and logout accept only calls the broker signed
 * ({@code X-User-Context}, issued within {@code login.user-context-max-age}); anything else gets 403.
 */
@Component
public class UserContextFilter extends OncePerRequestFilter {

    static final String HEADER = "X-User-Context";
    private static final Logger log = LoggerFactory.getLogger(UserContextFilter.class);
    private static final Set<String> PATHS = Set.of("/login", "/logout");

    private final byte[] secret;
    private final LoginProperties properties;

    public UserContextFilter(LoginProperties properties) {
        this.properties = properties;
        String configured = properties.userContextSecret();
        this.secret = configured == null || configured.isEmpty() ? null : configured.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return secret == null || !PATHS.contains(request.getRequestURI());
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
