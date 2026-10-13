package com.acme.broker.admin.security;

import java.util.EnumSet;
import java.util.Set;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import com.acme.broker.admin.config.AdminProperties;
import com.acme.broker.admin.web.ApiException;

/** Resolves the user of the current request; a user without any role is refused everywhere. */
@Component
public class CurrentUser {

    private final AdminProperties.Security settings;

    public CurrentUser(AdminProperties properties) {
        this.settings = properties.security();
    }

    public User get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "Oturum açılmamış.");
        }
        Set<Role> roles = EnumSet.noneOf(Role.class);
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String name = authority.getAuthority();
            if (name != null && name.startsWith("ROLE_")) {
                Role.parse(name.substring(5)).ifPresent(roles::add);
            }
        }
        String username = authentication.getName();
        String displayName = null;
        if (authentication instanceof JwtAuthenticationToken jwt) {
            displayName = jwt.getToken().getClaimAsString(settings.displayNameClaim());
        }
        User user = User.of(username, displayName, roles);
        if (user.roles().isEmpty()) {
            throw ApiException.forbidden("Broker Admin için rolünüz yok.");
        }
        return user;
    }
}
