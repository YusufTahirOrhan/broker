package com.acme.broker.admin.security;

import java.util.Optional;

/** Roles of the admin screens. ROUTE_EDITOR and APPROVER include reading. */
public enum Role {
    VIEWER, ROUTE_EDITOR, APPROVER;

    public String authority() {
        return "ROLE_" + name();
    }

    public static Optional<Role> parse(String value) {
        for (Role role : values()) {
            if (role.name().equals(value)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }
}
