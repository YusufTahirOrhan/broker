package com.acme.broker.admin.profile;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.acme.broker.admin.db.Rows;
import com.acme.broker.core.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** {@code response_profile}, its append-only history and its deployments. */
@Repository
public class ProfileRepository {

    private static final String COLUMNS = """
            id, name, description, definition, version, created_by, created_at, updated_by, updated_at""";

    private final JdbcClient jdbc;

    public ProfileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Row(long id, String name, String description, JsonNode definition, int version, String createdBy,
                      Instant createdAt, String updatedBy, Instant updatedAt) {
    }

    public record HistoryRow(int version, String action, JsonNode snapshot, String changedBy, Instant changedAt,
                             String comment) {
    }

    public record Deployment(long profileId, String environment, int version, String activatedBy, Instant activatedAt) {
    }

    /** A route active in an environment with this profile in the deployed version. */
    public record Usage(String environment, long routeId, String maintask, String subtask, int version) {
    }

    private static Row row(ResultSet rs, int index) throws SQLException {
        return new Row(rs.getLong("id"), rs.getString("name"), rs.getString("description"), Rows.json(rs, "definition"),
                rs.getInt("version"), rs.getString("created_by"), Rows.instant(rs, "created_at"),
                rs.getString("updated_by"), Rows.instant(rs, "updated_at"));
    }

    public List<Row> list() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM response_profile ORDER BY name").query(ProfileRepository::row).list();
    }

    public Optional<Row> find(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM response_profile WHERE id = :id").param("id", id)
                .query(ProfileRepository::row).optional();
    }

    public Optional<Row> findByName(String name) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM response_profile WHERE name = :name").param("name", name)
                .query(ProfileRepository::row).optional();
    }

    public long insert(String name, String description, JsonNode definition, String user, String comment) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO response_profile (name, description, definition, created_by, updated_by)
                        VALUES (:name, :description, :definition, :user, :user)
                        """)
                .param("name", name)
                .param("description", Rows.clob(description))
                .param("definition", Rows.clob(definition))
                .param("user", user)
                .update(keys, "id");
        long id = Rows.key(keys);
        history(id, "CREATE", comment);
        return id;
    }

    /** Writes a new version; empty when {@code expectedVersion} is no longer current. */
    public Optional<Integer> update(long id, int expectedVersion, String description, JsonNode definition, String user,
                                    String action, String comment) {
        int updated = jdbc.sql("""
                        UPDATE response_profile
                           SET description = :description, definition = :definition,
                               version = version + 1, updated_by = :user, updated_at = SYSTIMESTAMP
                         WHERE id = :id AND version = :expected
                        """)
                .param("id", id)
                .param("expected", expectedVersion)
                .param("description", Rows.clob(description))
                .param("definition", Rows.clob(definition))
                .param("user", user)
                .update();
        if (updated == 0) {
            return Optional.empty();
        }
        history(id, action, comment);
        return Optional.of(expectedVersion + 1);
    }

    /** History holds the whole row as it is after the change (snake_case columns, as {@code to_jsonb} wrote them). */
    private void history(long id, String action, String comment) {
        Row row = find(id).orElseThrow();
        ObjectNode snapshot = Json.object();
        snapshot.put("id", row.id());
        snapshot.put("name", row.name());
        snapshot.put("description", row.description());
        snapshot.set("definition", row.definition());
        snapshot.put("version", row.version());
        snapshot.put("created_by", row.createdBy());
        snapshot.put("created_at", row.createdAt().toString());
        snapshot.put("updated_by", row.updatedBy());
        snapshot.put("updated_at", row.updatedAt().toString());
        jdbc.sql("""
                        INSERT INTO response_profile_history (profile_id, version, action, snapshot, changed_by,
                                                              change_note)
                        VALUES (:id, :version, :action, :snapshot, :user, :comment)
                        """)
                .param("id", id)
                .param("version", row.version())
                .param("action", action)
                .param("snapshot", Rows.clob(snapshot))
                .param("user", row.updatedBy())
                .param("comment", Rows.clob(comment))
                .update();
    }

    public List<HistoryRow> history(long id) {
        return jdbc.sql("""
                        SELECT version, action, snapshot, changed_by, changed_at, change_note
                          FROM response_profile_history WHERE profile_id = :id ORDER BY version DESC
                        """)
                .param("id", id)
                .query(ProfileRepository::historyRow).list();
    }

    public Optional<HistoryRow> history(long id, int version) {
        return jdbc.sql("""
                        SELECT version, action, snapshot, changed_by, changed_at, change_note
                          FROM response_profile_history WHERE profile_id = :id AND version = :version
                        """)
                .param("id", id)
                .param("version", version)
                .query(ProfileRepository::historyRow).optional();
    }

    private static HistoryRow historyRow(ResultSet rs, int index) throws SQLException {
        return new HistoryRow(rs.getInt("version"), rs.getString("action"), Rows.json(rs, "snapshot"),
                rs.getString("changed_by"), Rows.instant(rs, "changed_at"), rs.getString("change_note"));
    }

    public List<Deployment> deployments() {
        return jdbc.sql("""
                        SELECT d.profile_id, d.environment, d.version, d.activated_by, d.activated_at
                          FROM response_profile_deployment d JOIN environment e ON e.code = d.environment
                         ORDER BY e.sort_order
                        """)
                .query((rs, index) -> new Deployment(rs.getLong("profile_id"), rs.getString("environment"),
                        rs.getInt("version"), rs.getString("activated_by"), Rows.instant(rs, "activated_at")))
                .list();
    }

    public List<Deployment> deployments(long profileId) {
        return deployments().stream().filter(deployment -> deployment.profileId() == profileId).toList();
    }

    public Optional<Deployment> deployment(long profileId, String environment) {
        return deployments(profileId).stream().filter(deployment -> deployment.environment().equals(environment))
                .findFirst();
    }

    public void upsertDeployment(long profileId, String environment, int version, String user) {
        Rows.merge(() -> jdbc.sql("""
                        MERGE INTO response_profile_deployment d
                        USING dual ON (d.profile_id = :id AND d.environment = :environment)
                         WHEN MATCHED THEN UPDATE
                              SET d.version = :version, d.activated_by = :user, d.activated_at = SYSTIMESTAMP
                         WHEN NOT MATCHED THEN INSERT (profile_id, environment, version, activated_by)
                              VALUES (:id, :environment, :version, :user)
                        """)
                .param("id", profileId)
                .param("environment", environment)
                .param("version", version)
                .param("user", user)
                .update());
    }

    /** Routes whose deployed version uses the profile, per environment. */
    public List<Usage> usage(long profileId) {
        return jdbc.sql("""
                        SELECT d.environment, d.definition_id, p.maintask, r.subtask, d.version
                          FROM route_deployment d
                          JOIN route_definition_history h ON h.definition_id = d.definition_id AND h.version = d.version
                          JOIN route_definition r ON r.id = d.definition_id
                          JOIN project p ON p.id = r.project_id
                          JOIN environment e ON e.code = d.environment
                         WHERE d.status = 'ACTIVE' AND JSON_VALUE(h.snapshot, '$.response_profile_id' RETURNING NUMBER) = :id
                         ORDER BY e.sort_order, p.maintask, r.subtask
                        """)
                .param("id", profileId)
                .query((rs, index) -> new Usage(rs.getString("environment"), rs.getLong("definition_id"),
                        rs.getString("maintask"), rs.getString("subtask"), rs.getInt("version")))
                .list();
    }
}
