package com.acme.broker.login.koordinator;

import java.util.List;
import java.util.Optional;

/**
 * Fixed answers for a developer's machine without a Koordinatör ({@code koordinator.mode: static}, only in the
 * {@code local} profile): every user gets the same permissions.
 */
public class StaticKoordinatorClient implements KoordinatorClient {

    private final List<String> permissions;
    private final List<String> projects;

    public StaticKoordinatorClient(List<String> permissions, List<String> projects) {
        this.permissions = List.copyOf(permissions);
        this.projects = List.copyOf(projects);
    }

    @Override
    public Optional<List<String>> permissions(String uid) {
        return Optional.of(permissions);
    }

    @Override
    public List<String> projectCodes() {
        return projects;
    }
}
