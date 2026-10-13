package com.acme.broker.admin.db;

import java.time.Instant;
import java.util.List;

import com.acme.broker.core.diff.JsonDiff;

import tools.jackson.databind.JsonNode;

/** Shared shapes of version history screens; routes and profiles use the same pattern. */
public final class Versions {

    private Versions() {
    }

    /** A history entry; {@code activeIn} lists the environments where this version is active now. */
    public record Entry(int version, String action, String changedBy, Instant changedAt, String comment,
                        List<String> activeIn) {
    }

    public record Change(String path, JsonNode before, JsonNode after) {
    }

    public static List<Change> changes(JsonNode before, JsonNode after) {
        return JsonDiff.diff(before, after).stream()
                .map(change -> new Change(change.path(), change.before(), change.after()))
                .toList();
    }
}
