package com.acme.yapi.admin.authz;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * The permissions of each environment, owned by its Koordinatör. Permissions are opened and assigned there; Yapi
 * only reads them.
 */
public interface AuthorizationService {

    record Permission(String code, String description) {
    }

    /**
     * The project's permissions in the environment: those of its Koordinatör project ({@code externalProjectId}),
     * or, where projects are not linked (memory), those with its prefix.
     */
    List<Permission> list(String environment, String externalProjectId, String prefix);

    /** The given codes that exist in the environment, in any project. */
    Set<String> existing(String environment, Collection<String> codes);
}
