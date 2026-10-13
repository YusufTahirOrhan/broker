package com.acme.broker.admin.route;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.acme.broker.admin.db.Rows;
import com.acme.broker.core.json.Json;

import tools.jackson.databind.JsonNode;

/** {@code route_definition}, its append-only history and {@code route_deployment}. */
@Repository
public class RouteRepository {

    private static final String SELECT = """
            SELECT r.id, r.project_id, p.maintask, p.permission_prefix, r.subtask, r.http_method, r.path_template,
                   r.permissions, r.foreign_permissions, r.anonymous, r.response_profile_id, rp.name AS profile_name,
                   r.definition::text AS definition, r.spec_operation::text AS spec_operation, r.spec_fingerprint,
                   r.status, r.version, r.created_by, r.created_at, r.updated_by, r.updated_at
              FROM route_definition r
              JOIN project p ON p.id = r.project_id
              LEFT JOIN response_profile rp ON rp.id = r.response_profile_id
            """;

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
                           AND (CAST(:status AS text) IS NULL OR r.status = :status)
                           AND (CAST(:query AS text) IS NULL
                                OR lower(r.subtask COLLATE "C") LIKE '%' || lower(CAST(:query AS text) COLLATE "C") || '%'
                                OR r.path_template LIKE '%' || CAST(:query AS text) || '%')
                         ORDER BY r.subtask
                        """)
                .param("project", projectId)
                .param("status", status == null || status.isBlank() ? null : status)
                .param("query", query == null || query.isBlank() ? null : query)
                .query(RouteRepository::row).list();
    }

    public Optional<Row> find(long id) {
        return jdbc.sql(SELECT + " WHERE r.id = :id").param("id", id).query(RouteRepository::row).optional();
    }

    public long insert(long projectId, String subtask, Content content, String user, String comment) {
        long id = jdbc.sql("""
                        INSERT INTO route_definition (project_id, subtask, http_method, path_template, permissions,
                                                      foreign_permissions, anonymous, response_profile_id, definition,
                                                      spec_operation, spec_fingerprint, created_by, updated_by)
                        VALUES (:project, :subtask, :method, :path, %s, %s, :anonymous, :profile,
                                CAST(:definition AS jsonb), CAST(:specOperation AS jsonb), :fingerprint, :user, :user)
                        RETURNING id
                        """.formatted(Rows.textArray("permissions"), Rows.textArray("foreign")))
                .param("project", projectId)
                .param("subtask", subtask)
                .param("method", content.httpMethod())
                .param("path", content.pathTemplate())
                .param("permissions", Rows.jsonList(content.permissions()))
                .param("foreign", Rows.jsonList(content.foreignPermissions()))
                .param("anonymous", content.anonymous())
                .param("profile", content.responseProfileId())
                .param("definition", Json.write(content.definition()))
                .param("specOperation", content.specOperation() == null ? null : Json.write(content.specOperation()))
                .param("fingerprint", content.specFingerprint())
                .param("user", user)
                .query(Long.class).single();
        history(id, "CREATE", comment);
        return id;
    }

    /** A new version with this content; empty when {@code expectedVersion} is no longer current. */
    public Optional<Integer> update(long id, int expectedVersion, Content content, String user, String action,
                                    String comment) {
        Optional<Integer> version = jdbc.sql("""
                        UPDATE route_definition
                           SET http_method = :method, path_template = :path, permissions = %s,
                               foreign_permissions = %s, anonymous = :anonymous, response_profile_id = :profile,
                               definition = CAST(:definition AS jsonb), spec_operation = CAST(:specOperation AS jsonb),
                               spec_fingerprint = :fingerprint, version = version + 1, updated_by = :user,
                               updated_at = now()
                         WHERE id = :id AND version = :expected
                        RETURNING version
                        """.formatted(Rows.textArray("permissions"), Rows.textArray("foreign")))
                .param("id", id)
                .param("expected", expectedVersion)
                .param("method", content.httpMethod())
                .param("path", content.pathTemplate())
                .param("permissions", Rows.jsonList(content.permissions()))
                .param("foreign", Rows.jsonList(content.foreignPermissions()))
                .param("anonymous", content.anonymous())
                .param("profile", content.responseProfileId())
                .param("definition", Json.write(content.definition()))
                .param("specOperation", content.specOperation() == null ? null : Json.write(content.specOperation()))
                .param("fingerprint", content.specFingerprint())
                .param("user", user)
                .query(Integer.class).optional();
        version.ifPresent(ignored -> history(id, action, comment));
        return version;
    }

    public Optional<Integer> status(long id, int expectedVersion, String status, String user, String action,
                                    String comment) {
        Optional<Integer> version = jdbc.sql("""
                        UPDATE route_definition
                           SET status = :status, version = version + 1, updated_by = :user, updated_at = now()
                         WHERE id = :id AND version = :expected
                        RETURNING version
                        """)
                .param("id", id)
                .param("expected", expectedVersion)
                .param("status", status)
                .param("user", user)
                .query(Integer.class).optional();
        version.ifPresent(ignored -> history(id, action, comment));
        return version;
    }

    private void history(long id, String action, String comment) {
        jdbc.sql("""
                        INSERT INTO route_definition_history (definition_id, version, action, snapshot, changed_by, comment)
                        SELECT id, version, :action, to_jsonb(rd), updated_by, :comment
                          FROM route_definition rd WHERE id = :id
                        """)
                .param("id", id)
                .param("action", action)
                .param("comment", comment)
                .update();
    }

    public List<HistoryRow> history(long id) {
        return jdbc.sql("""
                        SELECT version, action, snapshot::text AS snapshot, changed_by, changed_at, comment
                          FROM route_definition_history WHERE definition_id = :id ORDER BY version DESC
                        """)
                .param("id", id)
                .query(RouteRepository::historyRow).list();
    }

    public Optional<HistoryRow> history(long id, int version) {
        return jdbc.sql("""
                        SELECT version, action, snapshot::text AS snapshot, changed_by, changed_at, comment
                          FROM route_definition_history WHERE definition_id = :id AND version = :version
                        """)
                .param("id", id)
                .param("version", version)
                .query(RouteRepository::historyRow).optional();
    }

    private static HistoryRow historyRow(ResultSet rs, int index) throws SQLException {
        return new HistoryRow(rs.getInt("version"), rs.getString("action"), Rows.json(rs, "snapshot"),
                rs.getString("changed_by"), Rows.instant(rs, "changed_at"), rs.getString("comment"));
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
        jdbc.sql("""
                        INSERT INTO route_deployment (definition_id, environment, version, status, activated_by,
                                                      last_probe_status, last_probe_at)
                        VALUES (:id, :environment, :version, :status, :user, :probe,
                                CASE WHEN CAST(:probe AS text) IS NULL THEN NULL ELSE now() END)
                        ON CONFLICT (definition_id, environment) DO UPDATE
                           SET version = EXCLUDED.version, status = EXCLUDED.status,
                               activated_by = EXCLUDED.activated_by, activated_at = now(),
                               last_probe_status = coalesce(EXCLUDED.last_probe_status, route_deployment.last_probe_status),
                               last_probe_at = coalesce(EXCLUDED.last_probe_at, route_deployment.last_probe_at)
                        """)
                .param("id", id)
                .param("environment", environment)
                .param("version", version)
                .param("status", status)
                .param("user", user)
                .param("probe", probeStatus)
                .update();
    }

    public void probed(long id, String environment, String probeStatus) {
        jdbc.sql("""
                        UPDATE route_deployment SET last_probe_status = :probe, last_probe_at = now()
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
                        SELECT r.id, r.project_id, p.maintask, r.subtask, r.status,
                               CASE WHEN :code = ANY(r.permissions) THEN 'OWN' ELSE 'FOREIGN' END AS usage
                          FROM route_definition r JOIN project p ON p.id = r.project_id
                         WHERE :code = ANY(r.permissions) OR :code = ANY(r.foreign_permissions)
                         ORDER BY p.maintask, r.subtask
                        """)
                .param("code", code)
                .query((rs, index) -> new PermissionUse(rs.getLong("id"), rs.getLong("project_id"),
                        rs.getString("maintask"), rs.getString("subtask"), rs.getString("usage"),
                        rs.getString("status")))
                .list();
    }
}
