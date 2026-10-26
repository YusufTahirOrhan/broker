package com.acme.yapi.envadmin.runtime;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.IntSupplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import com.acme.yapi.core.json.Json;

import tools.jackson.databind.JsonNode;

/**
 * The runtime DB as the Admin API writes it. Every write is one transaction: the row is merged only when the incoming
 * {@code promotion_id} is newer, and the config version moves only when a row did.
 */
@Repository
public class RuntimeStore {

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
            Optional<Long> version = bumpIf(merge(() -> jdbc.sql("""
                            MERGE INTO runtime_route r
                            USING dual ON (r.maintask = :maintask AND r.subtask = :subtask)
                             WHEN MATCHED THEN UPDATE
                                  SET r.definition_id = :definitionId, r.version = :version, r.http_method = :method,
                                      r.path_template = :path, r.permissions = :permissions, r.anonymous = :anonymous,
                                      r.response_profile = :profile, r.definition = :definition, r.status = 'ACTIVE',
                                      r.applied_by = :user, r.promotion_id = :promotionId, r.applied_at = SYSTIMESTAMP
                                WHERE r.promotion_id < :promotionId
                             WHEN NOT MATCHED THEN INSERT
                                  (maintask, subtask, definition_id, version, http_method, path_template, permissions,
                                   anonymous, response_profile, definition, status, applied_by, promotion_id)
                                  VALUES (:maintask, :subtask, :definitionId, :version, :method, :path, :permissions,
                                          :anonymous, :profile, :definition, 'ACTIVE', :user, :promotionId)
                            """)
                    .param("maintask", route.maintask())
                    .param("subtask", route.subtask())
                    .param("definitionId", route.definitionId())
                    .param("version", route.version())
                    .param("method", route.httpMethod())
                    .param("path", route.pathTemplate())
                    .param("permissions", Json.write(route.permissions()))
                    .param("anonymous", flag(route.anonymous()))
                    .param("profile", route.responseProfile())
                    .param("definition", clob(Json.write(route.definition())))
                    .param("user", route.actor())
                    .param("promotionId", route.promotionId())
                    .update()));
            if (version.isPresent()) {
                return new ApplyResult.Applied(version.get());
            }
            long current = routePromotion(route.maintask(), route.subtask()).orElse(-1L);
            return replayOrStale(current, route.promotionId());
        });
    }

    public ApplyResult applyProfile(ProfileSnapshot profile) {
        return transactions.execute(status -> {
            Optional<Long> version = bumpIf(merge(() -> jdbc.sql("""
                            MERGE INTO runtime_response_profile p
                            USING dual ON (p.name = :name)
                             WHEN MATCHED THEN UPDATE
                                  SET p.profile_id = :profileId, p.version = :version, p.definition = :definition,
                                      p.applied_by = :user, p.promotion_id = :promotionId, p.applied_at = SYSTIMESTAMP
                                WHERE p.promotion_id < :promotionId
                             WHEN NOT MATCHED THEN INSERT
                                  (name, profile_id, version, definition, applied_by, promotion_id)
                                  VALUES (:name, :profileId, :version, :definition, :user, :promotionId)
                            """)
                    .param("name", profile.name())
                    .param("profileId", profile.profileId())
                    .param("version", profile.version())
                    .param("definition", clob(Json.write(profile.definition())))
                    .param("user", profile.actor())
                    .param("promotionId", profile.promotionId())
                    .update()));
            if (version.isPresent()) {
                return new ApplyResult.Applied(version.get());
            }
            long current = jdbc.sql("SELECT promotion_id FROM runtime_response_profile WHERE name = :name")
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
            Optional<Long> version = bumpIf(jdbc.sql("""
                            UPDATE runtime_route
                               SET status = 'DISABLED', applied_by = :user, promotion_id = :promotionId,
                                   applied_at = SYSTIMESTAMP
                             WHERE maintask = :maintask AND subtask = :subtask AND promotion_id < :promotionId
                            """)
                    .param("maintask", maintask)
                    .param("subtask", subtask)
                    .param("promotionId", promotionId)
                    .param("user", actor)
                    .update());
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
        return transactions.execute(status -> bumpIf(merge(() -> jdbc.sql("""
                        MERGE INTO runtime_project p
                        USING dual ON (p.maintask = :maintask)
                         WHEN MATCHED THEN UPDATE
                              SET p.base_url = :baseUrl, p.enabled = :enabled, p.updated_at = SYSTIMESTAMP
                            WHERE p.base_url <> :baseUrl OR p.enabled <> :enabled
                         WHEN NOT MATCHED THEN INSERT (maintask, base_url, enabled)
                              VALUES (:maintask, :baseUrl, :enabled)
                        """)
                .param("maintask", maintask)
                .param("baseUrl", baseUrl)
                .param("enabled", flag(enabled))
                .update())));
    }

    /**
     * Moves the config version when the write changed a row. The version row is the last lock every write takes, so
     * concurrent writes queue on it in the same order and the version a write returns is the one it produced.
     */
    private Optional<Long> bumpIf(int changed) {
        if (changed == 0) {
            return Optional.empty();
        }
        jdbc.sql("UPDATE runtime_config_version SET version = version + 1, updated_at = SYSTIMESTAMP WHERE id = 1")
                .update();
        return Optional.of(configVersion().version());
    }

    /**
     * Runs a MERGE once more when a concurrent insert of the same key won the race (ORA-00001): the second run sees
     * the committed row and takes the update branch.
     */
    private static int merge(IntSupplier statement) {
        try {
            return statement.getAsInt();
        } catch (DuplicateKeyException e) {
            return statement.getAsInt();
        }
    }

    private static int flag(boolean value) {
        return value ? 1 : 0;
    }

    /** Definitions can be larger than a VARCHAR2 bind; they are bound as CLOB. */
    private static SqlParameterValue clob(String text) {
        return new SqlParameterValue(Types.CLOB, text);
    }

    public ConfigVersion configVersion() {
        return jdbc.sql("SELECT version, updated_at FROM runtime_config_version WHERE id = 1")
                .query((rs, row) -> new ConfigVersion(rs.getLong("version"), instant(rs, "updated_at")))
                .single();
    }

    public Optional<ProjectRow> project(String maintask) {
        return jdbc.sql("""
                        SELECT maintask, base_url, enabled, updated_at
                          FROM runtime_project WHERE maintask = :maintask
                        """)
                .param("maintask", maintask)
                .query(RuntimeStore::projectRow)
                .optional();
    }

    public List<ProjectRow> projects() {
        return jdbc.sql("SELECT maintask, base_url, enabled, updated_at FROM runtime_project ORDER BY maintask")
                .query(RuntimeStore::projectRow)
                .list();
    }

    public List<ProfileRow> profiles() {
        return jdbc.sql("""
                        SELECT name, profile_id, version, promotion_id, applied_by, applied_at
                          FROM runtime_response_profile ORDER BY name
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
                          FROM runtime_route ORDER BY maintask, subtask
                        """)
                .query((rs, row) -> new RouteRow(rs.getString("maintask"), rs.getString("subtask"),
                        rs.getLong("definition_id"), rs.getInt("version"), rs.getString("http_method"),
                        rs.getString("path_template"), Json.stringList(rs.getString("permissions")),
                        rs.getBoolean("anonymous"), rs.getString("response_profile"), rs.getString("status"),
                        rs.getLong("promotion_id"), rs.getString("applied_by"), instant(rs, "applied_at")))
                .list();
    }

    private boolean projectExists(String maintask) {
        return jdbc.sql("SELECT count(*) FROM runtime_project WHERE maintask = :maintask")
                .param("maintask", maintask)
                .query(Long.class)
                .single() > 0;
    }

    private boolean profileExists(String name) {
        return jdbc.sql("SELECT count(*) FROM runtime_response_profile WHERE name = :name")
                .param("name", name)
                .query(Long.class)
                .single() > 0;
    }

    private Optional<Long> routePromotion(String maintask, String subtask) {
        return jdbc.sql("""
                        SELECT promotion_id FROM runtime_route
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
}
