package com.acme.yapi.admin.security;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.acme.yapi.admin.config.AdminProperties;
import com.acme.yapi.admin.web.ApiException;
import com.acme.yapi.admin.web.Problems;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Signs the request in with the login application's session ({@code X-Token}, {@code X-Uid}, {@code X-Clientip},
 * {@code X-User-Agent}). A missing header or an invalid session leaves the request anonymous, so {@code /api/**}
 * answers 401; a valid session without a Yapi permission is signed in without roles and gets 403. When the session
 * cannot be checked the request ends with the Admin API's error (502 {@code ENV_UNREACHABLE} and the like).
 */
final class SessionAuthenticationFilter extends OncePerRequestFilter {

    static final List<String> HEADERS = List.of("X-Token", "X-Uid", "X-Clientip", "X-User-Agent");

    private final SessionVerifier verifier;
    private final AdminProperties.Session settings;

    SessionAuthenticationFilter(SessionVerifier verifier, AdminProperties.Session settings) {
        this.verifier = verifier;
        this.settings = settings;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = request.getHeader("X-Token");
        String uid = request.getHeader("X-Uid");
        String clientIp = request.getHeader("X-Clientip");
        String userAgent = request.getHeader("X-User-Agent");
        if (blank(token) || blank(uid) || blank(clientIp) || blank(userAgent)) {
            chain.doFilter(request, response);
            return;
        }
        SessionVerifier.Verified session;
        try {
            session = verifier.verify(uid, token, clientIp, userAgent);
        } catch (ApiException e) {
            Problems.write(response, e.status(), e.code(), e.getMessage());
            return;
        }
        if (session.valid()) {
            List<SimpleGrantedAuthority> authorities = settings.rolesOf(session.permissions()).stream()
                    .map(role -> new SimpleGrantedAuthority(role.authority())).toList();
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(uid, null, authorities));
            SecurityContextHolder.setContext(context);
        }
        chain.doFilter(request, response);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
