package com.acme.broker.admin.project;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.acme.broker.admin.db.Rows;

@Repository
public class ProjectRepository {

    private static final String COLUMNS = """
            p.id, p.external_project_id, p.maintask, p.name, p.permission_prefix, p.catalog_status,
            p.catalog_synced_at, p.owner_team, p.description, p.default_response_profile_id,
            rp.name AS default_profile_name, p.created_by, p.created_at""";
    private static final String FROM = " FROM project p LEFT JOIN response_profile rp ON rp.id = p.default_response_profile_id";

    private final JdbcClient jdbc;

    public ProjectRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Row(long id, String externalProjectId, String maintask, String name, String permissionPrefix,
                      String catalogStatus, Instant catalogSyncedAt, String ownerTeam, String description,
                      Long defaultResponseProfileId, String defaultProfileName, String createdBy, Instant createdAt) {
    }

    public record EnvironmentRow(long projectId, String environment, String baseUrl, String specPath, boolean enabled) {
    }

    private static Row row(ResultSet rs, int index) throws SQLException {
        return new Row(rs.getLong("id"), rs.getString("external_project_id"), rs.getString("maintask"),
                rs.getString("name"), rs.getString("permission_prefix"), rs.getString("catalog_status"),
                Rows.instant(rs, "catalog_synced_at"), rs.getString("owner_team"), rs.getString("description"),
                Rows.longOrNull(rs, "default_response_profile_id"), rs.getString("default_profile_name"),
                rs.getString("created_by"), Rows.instant(rs, "created_at"));
    }

    public List<Row> list(String query, String catalogStatus) {
        return jdbc.sql("SELECT " + COLUMNS + FROM + """
                         WHERE (:pattern IS NULL
                                OR LOWER(p.maintask) LIKE LOWER(:pattern) ESCAPE '\\'
                                OR LOWER(p.name) LIKE LOWER(:pattern) ESCAPE '\\')
                           AND (:status IS NULL OR p.catalog_status = :status)
                         ORDER BY p.maintask
                        """)
                .param("pattern", Rows.contains(query))
                .param("status", Rows.text(catalogStatus == null || catalogStatus.isBlank() ? null : catalogStatus))
                .query(ProjectRepository::row).list();
    }

    public Optional<Row> find(long id) {
        return jdbc.sql("SELECT " + COLUMNS + FROM + " WHERE p.id = :id").param("id", id)
                .query(ProjectRepository::row).optional();
    }

    public List<Row> all() {
        return list(null, null);
    }

    /** Registered project per catalog ID. */
    public Map<String, Long> registeredByExternalId() {
        return jdbc.sql("SELECT external_project_id, id FROM project WHERE external_project_id IS NOT NULL")
                .query((rs, index) -> Map.entry(rs.getString("external_project_id"), rs.getLong("id")))
                .list().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public long insert(String externalProjectId, String maintask, String name, String prefix, String ownerTeam,
                       String description, Long defaultProfileId, String user) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO project (external_project_id, maintask, name, permission_prefix, catalog_status,
                                             catalog_synced_at, owner_team, description, default_response_profile_id,
                                             created_by)
                        VALUES (:external, :maintask, :name, :prefix, :status,
                                CASE WHEN :external IS NULL THEN NULL ELSE SYSTIMESTAMP END,
                                :ownerTeam, :description, :profile, :user)
                        """)
                .param("external", Rows.text(externalProjectId))
                .param("maintask", maintask)
                .param("name", name)
                .param("prefix", prefix)
                .param("status", externalProjectId == null ? "UNLINKED" : "LINKED")
                .param("ownerTeam", ownerTeam)
                .param("description", Rows.clob(description))
                .param("profile", defaultProfileId)
                .param("user", user)
                .update(keys, "id");
        return Rows.key(keys);
    }

    public void update(long id, String name, String ownerTeam, String description, Long defaultProfileId) {
        jdbc.sql("""
                        UPDATE project SET name = :name, owner_team = :ownerTeam, description = :description,
                                           default_response_profile_id = :profile
                         WHERE id = :id
                        """)
                .param("id", id)
                .param("name", name)
                .param("ownerTeam", ownerTeam)
                .param("description", Rows.clob(description))
                .param("profile", defaultProfileId)
                .update();
    }

    /** Links an UNLINKED project to the project service, or records what the last sync found. */
    public void catalog(long id, String externalProjectId, String name, String status) {
        jdbc.sql("""
                        UPDATE project SET external_project_id = :external, name = :name, catalog_status = :status,
                                           catalog_synced_at = SYSTIMESTAMP
                         WHERE id = :id
                        """)
                .param("id", id)
                .param("external", externalProjectId)
                .param("name", name)
                .param("status", status)
                .update();
    }

    public List<String> prefixes() {
        return jdbc.sql("SELECT permission_prefix FROM project").query(String.class).list();
    }

    public List<EnvironmentRow> environments(long projectId) {
        return jdbc.sql("""
                        SELECT pe.project_id, pe.environment, pe.base_url, pe.spec_path, pe.enabled
                          FROM project_environment pe JOIN environment e ON e.code = pe.environment
                         WHERE pe.project_id = :id ORDER BY e.sort_order
                        """)
                .param("id", projectId)
                .query((rs, index) -> new EnvironmentRow(rs.getLong("project_id"), rs.getString("environment"),
                        rs.getString("base_url"), rs.getString("spec_path"), rs.getBoolean("enabled")))
                .list();
    }

    public Optional<EnvironmentRow> environment(long projectId, String environment) {
        return environments(projectId).stream().filter(row -> row.environment().equals(environment)).findFirst();
    }

    public void upsertEnvironment(long projectId, String environment, String baseUrl, String specPath, boolean enabled) {
        Rows.merge(() -> jdbc.sql("""
                        MERGE INTO project_environment pe
                        USING dual ON (pe.project_id = :id AND pe.environment = :environment)
                         WHEN MATCHED THEN UPDATE
                              SET pe.base_url = :baseUrl, pe.spec_path = :specPath, pe.enabled = :enabled
                         WHEN NOT MATCHED THEN INSERT (project_id, environment, base_url, spec_path, enabled)
                              VALUES (:id, :environment, :baseUrl, :specPath, :enabled)
                        """)
                .param("id", projectId)
                .param("environment", environment)
                .param("baseUrl", baseUrl)
                .param("specPath", specPath)
                .param("enabled", Rows.flag(enabled))
                .update());
    }

    /** How many subtasks (not archived, any project) use each permission with this prefix. */
    public Map<String, Long> permissionUsage(String prefix) {
        return jdbc.sql("""
                        SELECT rp.code, COUNT(DISTINCT rp.definition_id) AS uses
                          FROM route_permission rp JOIN route_definition r ON r.id = rp.definition_id
                         WHERE r.status = 'ACTIVE' AND SUBSTR(rp.code, 1, LENGTH(:prefix) + 1) = :prefix || '_'
                         GROUP BY rp.code
                        """)
                .param("prefix", prefix)
                .query((rs, index) -> Map.entry(rs.getString("code"), rs.getLong("uses")))
                .list().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

}
