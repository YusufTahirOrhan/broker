package com.acme.yapi.admin.route;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.acme.yapi.admin.db.Rows;

import tools.jackson.databind.JsonNode;

/** {@code route_definition}, its append-only history and {@code route_deployment}. */
@Repository
public class RouteRepository {

    private static final String PERMISSIONS = """
            (SELECT JSON_ARRAYAGG(rp.code ORDER BY rp.sort_order RETURNING CLOB)
               FROM route_permission rp WHERE rp.definition_id = r.id AND rp.kind = '%s')""";

    private static final String SELECT = """
            SELECT r.id, r.project_id, p.maintask, p.permission_prefix, r.subtask, r.http_method, r.path_template,
                   %s AS permissions, %s AS foreign_permissions, r.anonymous, r.response_profile_id,
                   pf.name AS profile_name, r.definition, r.spec_operation, r.spec_fingerprint,
                   r.status, r.version, r.created_by, r.created_at, r.updated_by, r.updated_at
              FROM route_definition r
              JOIN project p ON p.id = r.project_id
              LEFT JOIN response_profile pf ON pf.id = r.response_profile_id
            """.formatted(PERMISSIONS.formatted("OWN"), PERMISSIONS.formatted("FOREIGN"));

    private final JdbcClient jdbc;

    public RouteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Row(long id, long projectId, String maintask, String permissionPrefix, String subtask,
                      String httpMethod, String pathTemplate, List<String> permissions, List<String> foreignPermissions,
                      boolean anonymous, Long responseProfileId, String profileName, JsonNode definition,
                      JsonNode specOperation, String specFingerprint, String status, int version, String createdBy,
                      Instant createdAt, String updatedBy, Instant updatedAt) {
    }

    /** What a save writes; the rest is bookkeeping. */
    public record Content(String httpMethod, String pathTemplate, List<String> permissions,
                          List<String> foreignPermissions, boolean anonymous, Long responseProfileId,
                          JsonNode definition, JsonNode specOperation, String specFingerprint) {
    }

    public record HistoryRow(int version, String action, JsonNode snapshot, String changedBy, Instant changedAt,
                             String comment) {
    }

    public record Deployment(long definitionId, String environment, int version, String status, String activatedBy,
                             Instant activatedAt, String lastProbeStatus, Instant lastProbeAt) {
    }

    private static Row row(ResultSet rs, int index) throws SQLException {
        String fingerprint = rs.getString("spec_fingerprint");
        return new Row(rs.getLong("id"), rs.getLong("project_id"), rs.getString("maintask"),
                rs.getString("permission_prefix"), rs.getString("subtask"), rs.getString("http_method"),
                rs.getString("path_template"), Rows.strings(rs, "permissions"), Rows.strings(rs, "foreign_permissions"),
                rs.getBoolean("anonymous"), Rows.longOrNull(rs, "response_profile_id"), rs.getString("profile_name"),
                Rows.json(rs, "definition"), Rows.json(rs, "spec_operation"),
                fingerprint == null ? null : fingerprint.strip(), rs.getString("status"), rs.getInt("version"),
                rs.getString("created_by"), Rows.instant(rs, "created_at"), rs.getString("updated_by"),
                Rows.instant(rs, "updated_at"));
    }

    public List<Row> list(long projectId, String status, String query) {
        return jdbc.sql(SELECT + """
                         WHERE r.project_id = :project
                           AND (:status IS NULL OR r.status = :status)
                           AND (:pattern IS NULL
                                OR LOWER(r.subtask) LIKE LOWER(:pattern) ESCAPE '\\'
                                OR r.path_template LIKE :pattern ESCAPE '\\')
                         ORDER BY r.subtask
                        """)
                .param("project", projectId)
                .param("status", Rows.text(status == null || status.isBlank() ? null : status))
                .param("pattern", Rows.contains(query))
                .query(RouteRepository::row).list();
    }

    /** Active subtasks whose current definition has an {@code alarmService} key; a null value is the caller's call. */
    private static final String ALARM_SERVICE = "r.status = 'ACTIVE' AND JSON_EXISTS(r.definition, '$.alarmService')";

    public List<Row> alarmServices() {
        return jdbc.sql(SELECT + " WHERE " + ALARM_SERVICE + " ORDER BY p.maintask, r.subtask")
                .query(RouteRepository::row).list();
    }

    public Optional<Row> find(long id) {
        return jdbc.sql(SELECT + " WHERE r.id = :id").param("id", id).query(RouteRepository::row).optional();
    }

    /** By name, without case, as the broker finds it. */
    public Optional<Row> find(String maintask, String subtask) {
        return jdbc.sql(SELECT + " WHERE LOWER(p.maintask) = LOWER(:maintask) AND LOWER(r.subtask) = LOWER(:subtask)")
                .param("maintask", maintask)
                .param("subtask", subtask)
                .query(RouteRepository::row).optional();
    }

    public long insert(long projectId, String subtask, Content content, String user, String comment) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO route_definition (project_id, subtask, http_method, path_template, anonymous,
                                                      response_profile_id, definition, spec_operation, spec_fingerprint,
                                                      created_by, updated_by)
                        VALUES (:project, :subtask, :method, :path, :anonymous, :profile, :definition, :specOperation,
                                :fingerprint, :user, :user)
                        """)
                .param("project", projectId)
                .param("subtask", subtask)
                .param("method", content.httpMethod())
                .param("path", content.pathTemplate())
                .param("anonymous", Rows.flag(content.anonymous()))
                .param("profile", content.responseProfileId())
                .param("definition", Rows.clob(content.definition()))
                .param("specOperation", Rows.clob(content.specOperation()))
                .param("fingerprint", content.specFingerprint())
                .param("user", user)
                .update(keys, "id");
        long id = Rows.key(keys);
        permissions(id, content);
        history(id, "CREATE", comment);
        return id;
    }

    /** A new version with this content; empty when {@code expectedVersion} is no longer current. */
    public Optional<Integer> update(long id, int expectedVersion, Content content, String user, String action,
                                    String comment) {
        int updated = jdbc.sql("""
                        UPDATE route_definition
                           SET http_method = :method, path_template = :path, anonymous = :anonymous,
                               response_profile_id = :profile, definition = :definition,
                               spec_operation = :specOperation, spec_fingerprint = :fingerprint,
                               version = version + 1, updated_by = :user, updated_at = SYSTIMESTAMP
                         WHERE id = :id AND version = :expected
                        """)
                .param("id", id)
                .param("expected", expectedVersion)
                .param("method", content.httpMethod())
                .param("path", content.pathTemplate())
                .param("anonymous", Rows.flag(content.anonymous()))
                .param("profile", content.responseProfileId())
                .param("definition", Rows.clob(content.definition()))
                .param("specOperation", Rows.clob(content.specOperation()))
                .param("fingerprint", content.specFingerprint())
                .param("user", user)
                .update();
        if (updated == 0) {
            return Optional.empty();
        }
        permissions(id, content);
        history(id, action, comment);
        return Optional.of(expectedVersion + 1);
    }

    public Optional<Integer> status(long id, int expectedVersion, String status, String user, String action,
                                    String comment) {
        int updated = jdbc.sql("""
                        UPDATE route_definition
                           SET status = :status, version = version + 1, updated_by = :user, updated_at = SYSTIMESTAMP
                         WHERE id = :id AND version = :expected
                        """)
                .param("id", id)
                .param("expected", expectedVersion)
                .param("status", status)
                .param("user", user)
                .update();
        if (updated == 0) {
            return Optional.empty();
        }
        history(id, action, comment);
        return Optional.of(expectedVersion + 1);
    }

    /** The route's permission lists are rewritten whole, in their order. */
    private void permissions(long id, Content content) {
        jdbc.sql("DELETE FROM route_permission WHERE definition_id = :id").param("id", id).update();
        insertPermissions(id, "OWN", content.permissions());
        insertPermissions(id, "FOREIGN", content.foreignPermissions());
    }

    private void insertPermissions(long id, String kind, List<String> codes) {
        List<String> values = codes == null ? List.of() : codes;
        for (int i = 0; i < values.size(); i++) {
            jdbc.sql("""
                            INSERT INTO route_permission (definition_id, kind, sort_order, code)
                            VALUES (:id, :kind, :position, :code)
                            """)
                    .param("id", id)
                    .param("kind", kind)
                    .param("position", i)
                    .param("code", values.get(i))
                    .update();
        }
    }

    /** History holds the whole row as it is after the change, with its permission lists (see {@link RouteSnapshots}). */
    private void history(long id, String action, String comment) {
        Row row = find(id).orElseThrow();
        jdbc.sql("""
                        INSERT INTO route_definition_history (definition_id, version, action, snapshot, changed_by,
                                                              change_note)
                        VALUES (:id, :version, :action, :snapshot, :user, :comment)
                        """)
                .param("id", id)
                .param("version", row.version())
                .param("action", action)
                .param("snapshot", Rows.clob(RouteSnapshots.stored(row)))
                .param("user", row.updatedBy())
                .param("comment", Rows.clob(comment))
                .update();
    }

    public List<HistoryRow> history(long id) {
        return jdbc.sql("""
                        SELECT version, action, snapshot, changed_by, changed_at, change_note
                          FROM route_definition_history WHERE definition_id = :id ORDER BY version DESC
                        """)
                .param("id", id)
                .query(RouteRepository::historyRow).list();
    }

    public Optional<HistoryRow> history(long id, int version) {
        return jdbc.sql("""
                        SELECT version, action, snapshot, changed_by, changed_at, change_note
                          FROM route_definition_history WHERE definition_id = :id AND version = :version
                        """)
                .param("id", id)
                .param("version", version)
                .query(RouteRepository::historyRow).optional();
    }

    private static HistoryRow historyRow(ResultSet rs, int index) throws SQLException {
        return new HistoryRow(rs.getInt("version"), rs.getString("action"), Rows.json(rs, "snapshot"),
                rs.getString("changed_by"), Rows.instant(rs, "changed_at"), rs.getString("change_note"));
    }

    private static final String DEPLOYMENTS = """
            SELECT d.definition_id, d.environment, d.version, d.status, d.activated_by, d.activated_at,
                   d.last_probe_status, d.last_probe_at
              FROM route_deployment d JOIN environment e ON e.code = d.environment
            """;

    private static Deployment deployment(ResultSet rs, int index) throws SQLException {
        return new Deployment(rs.getLong("definition_id"), rs.getString("environment"), rs.getInt("version"),
                rs.getString("status"), rs.getString("activated_by"), Rows.instant(rs, "activated_at"),
                rs.getString("last_probe_status"), Rows.instant(rs, "last_probe_at"));
    }

    public List<Deployment> deploymentsOfProject(long projectId) {
        return jdbc.sql(DEPLOYMENTS + """
                         JOIN route_definition r ON r.id = d.definition_id
                        WHERE r.project_id = :project ORDER BY e.sort_order
                        """)
                .param("project", projectId)
                .query(RouteRepository::deployment).list();
    }

    public List<Deployment> deploymentsOfAlarmServices() {
        return jdbc.sql(DEPLOYMENTS + " JOIN route_definition r ON r.id = d.definition_id WHERE " + ALARM_SERVICE
                        + " ORDER BY e.sort_order")
                .query(RouteRepository::deployment).list();
    }

    public List<Deployment> deployments(long id) {
        return jdbc.sql(DEPLOYMENTS + " WHERE d.definition_id = :id ORDER BY e.sort_order").param("id", id)
                .query(RouteRepository::deployment).list();
    }

    public List<Deployment> activeDeployments(String environment) {
        return jdbc.sql(DEPLOYMENTS + " WHERE d.environment = :environment AND d.status = 'ACTIVE'")
                .param("environment", environment)
                .query(RouteRepository::deployment).list();
    }

    public Optional<Deployment> deployment(long id, String environment) {
        return deployments(id).stream().filter(deployment -> deployment.environment().equals(environment)).findFirst();
    }

    public void upsertDeployment(long id, String environment, int version, String status, String user,
                                 String probeStatus) {
        Rows.merge(() -> jdbc.sql("""
                        MERGE INTO route_deployment d
                        USING dual ON (d.definition_id = :id AND d.environment = :environment)
                         WHEN MATCHED THEN UPDATE
                              SET d.version = :version, d.status = :status, d.activated_by = :user,
                                  d.activated_at = SYSTIMESTAMP,
                                  d.last_probe_status = NVL(:probe, d.last_probe_status),
                                  d.last_probe_at = CASE WHEN :probe IS NULL THEN d.last_probe_at ELSE SYSTIMESTAMP END
                         WHEN NOT MATCHED THEN INSERT
                              (definition_id, environment, version, status, activated_by, last_probe_status,
                               last_probe_at)
                              VALUES (:id, :environment, :version, :status, :user, :probe,
                                      CASE WHEN :probe IS NULL THEN NULL ELSE SYSTIMESTAMP END)
                        """)
                .param("id", id)
                .param("environment", environment)
                .param("version", version)
                .param("status", status)
                .param("user", user)
                .param("probe", Rows.text(probeStatus))
                .update());
    }

    public void probed(long id, String environment, String probeStatus) {
        jdbc.sql("""
                        UPDATE route_deployment SET last_probe_status = :probe, last_probe_at = SYSTIMESTAMP
                         WHERE definition_id = :id AND environment = :environment
                        """)
                .param("id", id)
                .param("environment", environment)
                .param("probe", probeStatus)
                .update();
    }

    public record PermissionUse(long routeId, long projectId, String maintask, String subtask, String usage,
                                String status) {
    }

    public List<PermissionUse> usingPermission(String code) {
        return jdbc.sql("""
                        SELECT r.id, r.project_id, p.maintask, r.subtask, r.status, rp.kind
                          FROM route_permission rp
                          JOIN route_definition r ON r.id = rp.definition_id
                          JOIN project p ON p.id = r.project_id
                         WHERE rp.code = :code
                         ORDER BY p.maintask, r.subtask
                        """)
                .param("code", code)
                .query((rs, index) -> new PermissionUse(rs.getLong("id"), rs.getLong("project_id"),
                        rs.getString("maintask"), rs.getString("subtask"), rs.getString("kind"),
                        rs.getString("status")))
                .list();
    }
}
