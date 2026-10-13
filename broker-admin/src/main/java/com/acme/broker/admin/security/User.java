package com.acme.broker.admin.security;

import java.util.EnumSet;
import java.util.Set;

import org.springframework.http.HttpStatus;

import com.acme.broker.admin.web.ApiException;

/** The signed-in user; {@code roles} already include VIEWER when the user has any role. */
public record User(String username, String displayName, Set<Role> roles) {

    public static User of(String username, String displayName, Set<Role> granted) {
        Set<Role> roles = granted.isEmpty() ? EnumSet.noneOf(Role.class) : EnumSet.copyOf(granted);
        if (!roles.isEmpty()) {
            roles.add(Role.VIEWER);
        }
        return new User(username, displayName == null ? username : displayName, Set.copyOf(roles));
    }

    public boolean has(Role role) {
        return roles.contains(role);
    }

    public User require(Role role) {
        if (!has(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Bu işlem için " + role + " rolü gerekli.");
        }
        return this;
    }
}
