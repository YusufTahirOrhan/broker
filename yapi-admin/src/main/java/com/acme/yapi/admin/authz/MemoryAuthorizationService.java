package com.acme.yapi.admin.authz;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.acme.yapi.core.naming.Names;

/** Local development and tests: permissions per environment from {@code yapi.admin.authorization.permissions}. */
public class MemoryAuthorizationService implements AuthorizationService {

    private final Map<String, Map<String, String>> byEnvironment = new ConcurrentHashMap<>();

    public MemoryAuthorizationService(Map<String, List<String>> seed) {
        seed.forEach((environment, codes) -> codes.forEach(code -> create(environment, code, null)));
    }

    @Override
    public List<Permission> list(String environment, String prefix) {
        return byEnvironment.getOrDefault(environment, Map.of()).entrySet().stream()
                .filter(entry -> Names.belongsTo(entry.getKey(), prefix))
                .map(entry -> new Permission(entry.getKey(), entry.getValue()))
                .sorted(java.util.Comparator.comparing(Permission::code))
                .toList();
    }

    @Override
    public boolean create(String environment, String code, String description) {
        Map<String, String> permissions = byEnvironment.computeIfAbsent(environment, key -> new ConcurrentHashMap<>());
        return permissions.putIfAbsent(code, description == null ? "" : description) == null;
    }

    @Override
    public Set<String> existing(String environment, Collection<String> codes) {
        Map<String, String> permissions = byEnvironment.getOrDefault(environment, Map.of());
        Set<String> found = new LinkedHashSet<>();
        codes.stream().filter(permissions::containsKey).forEach(found::add);
        return found;
    }
}
