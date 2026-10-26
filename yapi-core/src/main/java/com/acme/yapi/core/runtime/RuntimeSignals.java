package com.acme.yapi.core.runtime;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

import com.acme.yapi.core.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Redis contract between the environment Admin API and the brokers. The Admin API publishes the new config version
 * on {@link #ROUTES_CHANGED_CHANNEL} after a commit; every broker writes the version it loaded under
 * {@code yapi:loaded:{instanceId}} with a short TTL, so the central admin can show "3/3 instances on v43".
 */
public final class RuntimeSignals {

    public static final String ROUTES_CHANGED_CHANNEL = "yapi:routes-changed";
    public static final String LOADED_KEY_PREFIX = "yapi:loaded:";
    public static final Duration LOADED_TTL = Duration.ofSeconds(60);

    private RuntimeSignals() {
    }

    /** What an instance reports: the config version it serves and when it loaded it. */
    public record Loaded(long version, Instant loadedAt) {

        public String encode() {
            ObjectNode node = Json.object();
            node.put("version", version);
            node.put("loadedAt", loadedAt.toString());
            return Json.write(node);
        }

        /** Reads {@code {"version": 43, "loadedAt": "..."}}; a bare number is accepted too. */
        public static Optional<Loaded> decode(String value) {
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            JsonNode node = Json.tryParse(value);
            if (node != null && node.isIntegralNumber()) {
                return Optional.of(new Loaded(node.longValue(), null));
            }
            if (node == null || !node.isObject() || node.get("version") == null || !node.get("version").isIntegralNumber()) {
                return Optional.empty();
            }
            Instant loadedAt = null;
            String time = Json.stringField(node, "loadedAt");
            if (time != null) {
                try {
                    loadedAt = Instant.parse(time);
                } catch (DateTimeParseException ignored) {
                    // a malformed time does not hide the version
                }
            }
            return Optional.of(new Loaded(node.get("version").longValue(), loadedAt));
        }
    }
}
