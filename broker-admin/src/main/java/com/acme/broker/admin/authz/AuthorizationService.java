package com.acme.broker.admin.authz;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/** The authorization service of each environment: owns permissions and their assignment to users. */
public interface AuthorizationService {

    record Permission(String code, String description) {
    }

    List<Permission> list(String environment, String prefix);

    /** @return false when the permission already existed */
    boolean create(String environment, String code, String description);

    /** The given codes that exist in the environment. */
    Set<String> existing(String environment, Collection<String> codes);
}
