package com.acme.yapi.admin.security;

import java.util.EnumSet;
import java.util.Set;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.acme.yapi.admin.web.ApiException;

/**
 * Resolves the user of the current request: the session's uid (the sicil) or the local user. A user without any
 * role is refused everywhere.
 */
@Component
public class CurrentUser {

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
        User user = User.of(authentication.getName(), null, roles);
        if (user.roles().isEmpty()) {
            throw ApiException.forbidden("Yapi Admin için rolünüz yok.");
        }
        return user;
    }
}
