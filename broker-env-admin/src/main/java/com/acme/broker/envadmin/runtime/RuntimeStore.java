package com.acme.broker.envadmin.runtime;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import com.acme.broker.core.json.Json;

import tools.jackson.databind.JsonNode;

/**
 * The runtime DB as the Admin API writes it. Every write is the single statement of the design document: the row is
 * upserted only when the incoming {@code promotion_id} is newer, and the config version moves only when a row did.
 */
@Repository
public class RuntimeStore {

    private static final String BUMP_VERSION = """
            UPDATE broker_runtime.runtime_config_version
               SET version = version + 1, updated_at = now()
             WHERE id = 1 AND EXISTS (SELECT 1 FROM applied)
            RETURNING version
            """;

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public RuntimeStore(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public record RouteSnapshot(String maintask, String subtask, long definitionId, int version, String httpMethod,
                                String pathTemplate, List<String> permissions, boolean anonymous,
                                String responseProfile, JsonNode definition, String actor, long promotionId) {
    }

    public record ProfileSnapshot(String name, long profileId, int version, JsonNode definition, String actor,
                                  long promotionId) {
    }

    public record ConfigVersion(long version, Instant updatedAt) {
    }

    public record ProjectRow(String maintask, String baseUrl, boolean enabled, Instant updatedAt) {
    }

    public record ProfileRow(String name, long profileId, int version, long promotionId, String appliedBy,
                             Instant appliedAt) {
    }

    public record RouteRow(String maintask, String subtask, long definitionId, int version, String httpMethod,
                           String pathTemplate, List<String> permissions, boolean anonymous, String responseProfile,
                           String status, long promotionId, String appliedBy, Instant appliedAt) {
    }

    public ApplyResult applyRoute(RouteSnapshot route) {
        return transactions.execute(status -> {
            if (!projectExists(route.maintask())) {
                return new ApplyResult.Missing("PROJECT_NOT_FOUND",
                        "Proje bu ortamda tanımlı değil: " + route.maintask());
            }
            if (route.responseProfile() != null && !profileExists(route.responseProfile())) {
                return new ApplyResult.Missing("PROFILE_NOT_FOUND",
                        "Yanıt profili bu ortamda yok: " + route.responseProfile() + "; önce profil taşınmalı");
            }
            Optional<Long> version = jdbc.sql("""
                            WITH applied AS (
                                INSERT INTO broker_runtime.runtime_route AS r
                                       (maintask, subtask, definition_id, version, http_method, path_template,
                                        permissions, anonymous, response_profile, definition, status,
                                        applied_by, promotion_id)
                                VALUES (:maintask, :subtask, :definitionId, :version, :method, :path,
                                        ARRAY(SELECT jsonb_array_elements_text(CAST(:permissions AS jsonb))),
                                        :anonymous, :profile, CAST(:definition AS jsonb), 'ACTIVE',
                                        :user, :promotionId)
                                ON CONFLICT (maintask, subtask) DO UPDATE
                                   SET definition_id = EXCLUDED.definition_id, version = EXCLUDED.version,
                                       http_method = EXCLUDED.http_method, path_template = EXCLUDED.path_template,
                                       permissions = EXCLUDED.permissions, anonymous = EXCLUDED.anonymous,
                                       response_profile = EXCLUDED.response_profile, definition = EXCLUDED.definition,
                                       status = EXCLUDED.status, applied_by = EXCLUDED.applied_by,
                                       promotion_id = EXCLUDED.promotion_id, applied_at = now()
                                 WHERE EXCLUDED.promotion_id > r.promotion_id
                                RETURNING 1
                            )
                            """ + BUMP_VERSION)
                    .param("maintask", route.maintask())
                    .param("subtask", route.subtask())
                    .param("definitionId", route.definitionId())
                    .param("version", route.version())
                    .param("method", route.httpMethod())
                    .param("path", route.pathTemplate())
                    .param("permissions", Json.write(route.permissions()))
                    .param("anonymous", route.anonymous())
                    .param("profile", route.responseProfile())
                    .param("definition", Json.write(route.definition()))
                    .param("user", route.actor())
                    .param("promotionId", route.promotionId())
                    .query(Long.class)
                    .optional();
            if (version.isPresent()) {
                return new ApplyResult.Applied(version.get());
            }
            long current = routePromotion(route.maintask(), route.subtask()).orElse(-1L);
            return replayOrStale(current, route.promotionId());
        });
    }

    public ApplyResult applyProfile(ProfileSnapshot profile) {
        return transactions.execute(status -> {
            Optional<Long> version = jdbc.sql("""
                            WITH applied AS (
                                INSERT INTO broker_runtime.runtime_response_profile AS p
                                       (name, profile_id, version, definition, applied_by, promotion_id)
                                VALUES (:name, :profileId, :version, CAST(:definition AS jsonb), :user, :promotionId)
                                ON CONFLICT (name) DO UPDATE
                                   SET profile_id = EXCLUDED.profile_id, version = EXCLUDED.version,
                                       definition = EXCLUDED.definition, applied_by = EXCLUDED.applied_by,
                                       promotion_id = EXCLUDED.promotion_id, applied_at = now()
                                 WHERE EXCLUDED.promotion_id > p.promotion_id
                                RETURNING 1
                            )
                            """ + BUMP_VERSION)
                    .param("name", profile.name())
                    .param("profileId", profile.profileId())
                    .param("version", profile.version())
                    .param("definition", Json.write(profile.definition()))
                    .param("user", profile.actor())
                    .param("promotionId", profile.promotionId())
                    .query(Long.class)
                    .optional();
            if (version.isPresent()) {
                return new ApplyResult.Applied(version.get());
            }
            long current = jdbc.sql("SELECT promotion_id FROM broker_runtime.runtime_response_profile WHERE name = :name")
                    .param("name", profile.name())
                    .query(Long.class)
                    .optional()
                    .orElse(-1L);
            return replayOrStale(current, profile.promotionId());
        });
    }

    /** Kill switch: the route stays in the table as DISABLED, so brokers drop it on their next load. */
    public ApplyResult disableRoute(String maintask, String subtask, long promotionId, String actor) {
        return transactions.execute(status -> {
            Optional<Long> version = jdbc.sql("""
                            WITH applied AS (
                                UPDATE broker_runtime.runtime_route
                                   SET status = 'DISABLED', applied_by = :user, promotion_id = :promotionId,
                                       applied_at = now()
                                 WHERE maintask = :maintask AND subtask = :subtask AND promotion_id < :promotionId
                                RETURNING 1
                            )
                            """ + BUMP_VERSION)
                    .param("maintask", maintask)
                    .param("subtask", subtask)
                    .param("promotionId", promotionId)
                    .param("user", actor)
                    .query(Long.class)
                    .optional();
            if (version.isPresent()) {
                return new ApplyResult.Applied(version.get());
            }
            Optional<Long> current = routePromotion(maintask, subtask);
            if (current.isEmpty()) {
                return new ApplyResult.Missing("ROUTE_NOT_FOUND",
                        "Route bu ortamda yok: " + maintask + " / " + subtask);
            }
            return replayOrStale(current.get(), promotionId);
        });
    }

    /**
     * Writes the project's base URL and switch. Brokers read both with every snapshot, so a change moves the config
     * version like a route change does. Returns the new version, or empty when nothing changed.
     */
    public Optional<Long> upsertProject(String maintask, String baseUrl, boolean enabled) {
        return transactions.execute(status -> jdbc.sql("""
                        WITH applied AS (
                            INSERT INTO broker_runtime.runtime_project AS p (maintask, base_url, enabled)
                            VALUES (:maintask, :baseUrl, :enabled)
                            ON CONFLICT (maintask) DO UPDATE
                               SET base_url = EXCLUDED.base_url, enabled = EXCLUDED.enabled, updated_at = now()
                             WHERE (p.base_url, p.enabled) IS DISTINCT FROM (EXCLUDED.base_url, EXCLUDED.enabled)
                            RETURNING 1
                        )
                        """ + BUMP_VERSION)
                .param("maintask", maintask)
                .param("baseUrl", baseUrl)
                .param("enabled", enabled)
                .query(Long.class)
                .optional());
    }

    public ConfigVersion configVersion() {
        return jdbc.sql("SELECT version, updated_at FROM broker_runtime.runtime_config_version WHERE id = 1")
                .query((rs, row) -> new ConfigVersion(rs.getLong("version"), instant(rs, "updated_at")))
                .single();
    }

    public Optional<ProjectRow> project(String maintask) {
        return jdbc.sql("""
                        SELECT maintask, base_url, enabled, updated_at
                          FROM broker_runtime.runtime_project WHERE maintask = :maintask
                        """)
                .param("maintask", maintask)
                .query(RuntimeStore::projectRow)
                .optional();
    }

    public List<ProjectRow> projects() {
        return jdbc.sql("SELECT maintask, base_url, enabled, updated_at FROM broker_runtime.runtime_project ORDER BY maintask")
                .query(RuntimeStore::projectRow)
                .list();
    }

    public List<ProfileRow> profiles() {
        return jdbc.sql("""
                        SELECT name, profile_id, version, promotion_id, applied_by, applied_at
                          FROM broker_runtime.runtime_response_profile ORDER BY name
                        """)
                .query((rs, row) -> new ProfileRow(rs.getString("name"), rs.getLong("profile_id"),
                        rs.getInt("version"), rs.getLong("promotion_id"), rs.getString("applied_by"),
                        instant(rs, "applied_at")))
                .list();
    }

    public List<RouteRow> routes() {
        return jdbc.sql("""
                        SELECT maintask, subtask, definition_id, version, http_method, path_template, permissions,
                               anonymous, response_profile, status, promotion_id, applied_by, applied_at
                          FROM broker_runtime.runtime_route ORDER BY maintask, subtask
                        """)
                .query((rs, row) -> new RouteRow(rs.getString("maintask"), rs.getString("subtask"),
                        rs.getLong("definition_id"), rs.getInt("version"), rs.getString("http_method"),
                        rs.getString("path_template"), strings(rs.getArray("permissions")),
                        rs.getBoolean("anonymous"), rs.getString("response_profile"), rs.getString("status"),
                        rs.getLong("promotion_id"), rs.getString("applied_by"), instant(rs, "applied_at")))
                .list();
    }

    private boolean projectExists(String maintask) {
        return jdbc.sql("SELECT count(*) FROM broker_runtime.runtime_project WHERE maintask = :maintask")
                .param("maintask", maintask)
                .query(Long.class)
                .single() > 0;
    }

    private boolean profileExists(String name) {
        return jdbc.sql("SELECT count(*) FROM broker_runtime.runtime_response_profile WHERE name = :name")
                .param("name", name)
                .query(Long.class)
                .single() > 0;
    }

    private Optional<Long> routePromotion(String maintask, String subtask) {
        return jdbc.sql("""
                        SELECT promotion_id FROM broker_runtime.runtime_route
                         WHERE maintask = :maintask AND subtask = :subtask
                        """)
                .param("maintask", maintask)
                .param("subtask", subtask)
                .query(Long.class)
                .optional();
    }

    private ApplyResult replayOrStale(long currentPromotionId, long requestedPromotionId) {
        long configVersion = configVersion().version();
        return currentPromotionId == requestedPromotionId
                ? new ApplyResult.AlreadyApplied(configVersion)
                : new ApplyResult.Stale(currentPromotionId, configVersion);
    }

    private static ProjectRow projectRow(ResultSet rs, int row) throws SQLException {
        return new ProjectRow(rs.getString("maintask"), rs.getString("base_url"), rs.getBoolean("enabled"),
                instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime time = rs.getObject(column, OffsetDateTime.class);
        return time == null ? null : time.toInstant();
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }
}
