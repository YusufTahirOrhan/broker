package com.acme.broker.envadmin.web;

import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;

import javax.security.auth.x500.X500Principal;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.acme.broker.core.json.Json;
import com.acme.broker.envadmin.config.EnvAdminProperties;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.node.ObjectNode;

/**
 * mTLS already rejects clients the trust store does not know ({@code server.ssl.client-auth=need}). When
 * {@code broker.env-admin.allowed-client-subjects} is set, this filter also requires the certificate subject to be
 * one of them, so only the central admin's certificate is accepted even if the trust store holds a whole CA.
 */
@Component
public class ClientCertificateFilter extends OncePerRequestFilter {

    static final String CERTIFICATE_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";

    private final List<X500Principal> allowed;

    public ClientCertificateFilter(EnvAdminProperties properties) {
        this.allowed = properties.allowedClientSubjects().stream().map(X500Principal::new).toList();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return allowed.isEmpty() || !request.getRequestURI().startsWith("/admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Object attribute = request.getAttribute(CERTIFICATE_ATTRIBUTE);
        if (attribute instanceof X509Certificate[] chainCertificates && chainCertificates.length > 0
                && allowed.contains(chainCertificates[0].getSubjectX500Principal())) {
            request.setAttribute(Audit.CLIENT_ATTRIBUTE, chainCertificates[0].getSubjectX500Principal().getName());
            chain.doFilter(request, response);
            return;
        }
        ResponseEntity<ObjectNode> problem = Problems.of(HttpStatus.FORBIDDEN, "CLIENT_NOT_ALLOWED",
                "İstemci sertifikası bu ortamın Admin API'sini çağıramaz.", List.of(), Map.of());
        response.setStatus(problem.getStatusCode().value());
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getOutputStream().write(Json.writeBytes(problem.getBody()));
    }
}
