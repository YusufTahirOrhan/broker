package com.acme.yapi.admin.authz;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.acme.yapi.core.naming.Names;

/**
 * Local development and tests: permissions per environment from {@code yapi.admin.authorization.permissions}; a
 * project's are those with its prefix.
 */
public class MemoryAuthorizationService implements AuthorizationService {

    private final Map<String, Map<String, String>> byEnvironment = new ConcurrentHashMap<>();

    public MemoryAuthorizationService(Map<String, List<String>> seed) {
        seed.forEach((environment, codes) -> codes.forEach(code -> byEnvironment
                .computeIfAbsent(environment, key -> new ConcurrentHashMap<>()).putIfAbsent(code, "")));
    }

    @Override
    public List<Permission> list(String environment, String externalProjectId, String prefix) {
        return byEnvironment.getOrDefault(environment, Map.of()).entrySet().stream()
                .filter(entry -> Names.belongsTo(entry.getKey(), prefix))
                .map(entry -> new Permission(entry.getKey(), entry.getValue()))
                .sorted(java.util.Comparator.comparing(Permission::code))
                .toList();
    }

    @Override
    public Set<String> existing(String environment, Collection<String> codes) {
        Map<String, String> permissions = byEnvironment.getOrDefault(environment, Map.of());
        Set<String> found = new LinkedHashSet<>();
        codes.stream().filter(permissions::containsKey).forEach(found::add);
        return found;
    }
}
