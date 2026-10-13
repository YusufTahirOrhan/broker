package com.acme.broker.runtime.session;

import java.util.List;
import java.util.Set;

/**
 * The verified user of a request. {@code permissionList} keeps the stored order for {@code perm_list} in the legacy
 * documents; {@code permissions} is for the any-of check.
 */
public record Session(String userId, List<String> permissionList, Set<String> permissions) {

    public static Session of(String userId, List<String> permissions) {
        return new Session(userId, List.copyOf(permissions), Set.copyOf(permissions));
    }
}
