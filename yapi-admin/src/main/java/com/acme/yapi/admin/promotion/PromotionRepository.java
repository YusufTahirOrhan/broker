package com.acme.yapi.admin.promotion;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.acme.yapi.admin.db.Rows;

import tools.jackson.databind.JsonNode;

/** {@code promotion_request}: promotion requests, approvals and the deployment audit trail. */
@Repository
public class PromotionRepository {

    private static final String SELECT = """
            SELECT pr.id, pr.definition_id, pr.version, pr.profile_id, pr.profile_version, pr.plugin_id,
                   pr.plugin_version, pr.source_env,
                   pr.target_env, pr.action, pr.status, pr.requested_by, pr.requested_at, pr.reason, pr.decided_by,
                   pr.decided_at, pr.decision_note, pr.probe_status, pr.probe_detail,
                   pr.config_version, pr.applied_at, pr.failure_reason,
                   CASE WHEN pr.definition_id IS NOT NULL THEN p.maintask || ' / ' || r.subtask
                        WHEN pr.profile_id IS NOT NULL THEN pf.name ELSE pl.name END AS subject_name,
                   r.project_id
              FROM promotion_request pr
              LEFT JOIN route_definition r ON r.id = pr.definition_id
              LEFT JOIN project p ON p.id = r.project_id
              LEFT JOIN response_profile pf ON pf.id = pr.profile_id
              LEFT JOIN plugin pl ON pl.id = pr.plugin_id
            """;

    private final JdbcClient jdbc;

    public PromotionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Row(long id, Long definitionId, Integer version, Long profileId, Integer profileVersion,
                      Long pluginId, Integer pluginVersion, String sourceEnv, String targetEnv, String action,
                      String status, String requestedBy, Instant requestedAt, String reason, String decidedBy,
                      Instant decidedAt, String decisionNote, String probeStatus, JsonNode probeDetail,
                      Long configVersion, Instant appliedAt, String failureReason, String subjectName, Long projectId) {

        public SubjectType type() {
            return definitionId != null ? SubjectType.ROUTE : profileId != null ? SubjectType.PROFILE
                    : SubjectType.PLUGIN;
        }

        public boolean route() {
            return type() == SubjectType.ROUTE;
        }

        public long subjectId() {
            return switch (type()) {
                case ROUTE -> definitionId;
                case PROFILE -> profileId;
                case PLUGIN -> pluginId;
            };
        }

        public int subjectVersion() {
            return switch (type()) {
                case ROUTE -> version;
                case PROFILE -> profileVersion;
                case PLUGIN -> pluginVersion;
            };
        }
    }

    private static Row row(ResultSet rs, int index) throws SQLException {
        return new Row(rs.getLong("id"), Rows.longOrNull(rs, "definition_id"), Rows.intOrNull(rs, "version"),
                Rows.longOrNull(rs, "profile_id"), Rows.intOrNull(rs, "profile_version"),
                Rows.longOrNull(rs, "plugin_id"), Rows.intOrNull(rs, "plugin_version"), rs.getString("source_env"),
                rs.getString("target_env"), rs.getString("action"), rs.getString("status"),
                rs.getString("requested_by"), Rows.instant(rs, "requested_at"), rs.getString("reason"),
                rs.getString("decided_by"), Rows.instant(rs, "decided_at"), rs.getString("decision_note"),
                rs.getString("probe_status"), Rows.json(rs, "probe_detail"), Rows.longOrNull(rs, "config_version"),
                Rows.instant(rs, "applied_at"), rs.getString("failure_reason"), rs.getString("subject_name"),
                Rows.longOrNull(rs, "project_id"));
    }

    public long insert(SubjectType type, long subjectId, int version, String sourceEnv, String targetEnv,
                       String action, String status, String user, String reason) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO promotion_request (definition_id, version, profile_id, profile_version, plugin_id,
                                                       plugin_version, source_env, target_env, action, status,
                                                       requested_by, reason)
                        VALUES (:definition, :version, :profile, :profileVersion, :plugin, :pluginVersion, :source,
                                :target, :action, :status, :user, :reason)
                        """)
                .param("definition", type == SubjectType.ROUTE ? subjectId : null)
                .param("version", type == SubjectType.ROUTE ? version : null)
                .param("profile", type == SubjectType.PROFILE ? subjectId : null)
                .param("profileVersion", type == SubjectType.PROFILE ? version : null)
                .param("plugin", type == SubjectType.PLUGIN ? subjectId : null)
                .param("pluginVersion", type == SubjectType.PLUGIN ? version : null)
                .param("source", sourceEnv)
                .param("target", targetEnv)
                .param("action", action)
                .param("status", status)
                .param("user", user)
                .param("reason", Rows.clob(reason))
                .update(keys, "id");
        return Rows.key(keys);
    }

    public Optional<Row> find(long id) {
        return jdbc.sql(SELECT + " WHERE pr.id = :id").param("id", id).query(PromotionRepository::row).optional();
    }

    public record Filter(List<String> statuses, String targetEnv, String subjectType, Long projectId, String requestedBy) {
    }

    public List<Row> page(Filter filter, int page, int size) {
        return jdbc.sql(SELECT + where(filter) + " ORDER BY pr.requested_at DESC, pr.id DESC OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY")
                .params(parameters(filter))
                .param("size", size)
                .param("offset", (long) page * size)
                .query(PromotionRepository::row).list();
    }

    public long count(Filter filter) {
        return jdbc.sql("SELECT count(*) FROM (" + SELECT + where(filter) + ") counted")
                .params(parameters(filter))
                .query(Long.class).single();
    }

    private static String where(Filter filter) {
        List<String> conditions = new ArrayList<>();
        if (!filter.statuses().isEmpty()) {
            conditions.add("pr.status IN (:statuses)");
        }
        if (filter.targetEnv() != null) {
            conditions.add("pr.target_env = :target");
        }
        SubjectType.parse(filter.subjectType())
                .ifPresent(type -> conditions.add("pr." + type.idColumn() + " IS NOT NULL"));
        if (filter.projectId() != null) {
            conditions.add("r.project_id = :project");
        }
        if (filter.requestedBy() != null) {
            conditions.add("pr.requested_by = :requestedBy");
        }
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    private static java.util.Map<String, Object> parameters(Filter filter) {
        java.util.Map<String, Object> parameters = new java.util.HashMap<>();
        if (!filter.statuses().isEmpty()) {
            parameters.put("statuses", filter.statuses());
        }
        if (filter.targetEnv() != null) {
            parameters.put("target", filter.targetEnv());
        }
        if (filter.projectId() != null) {
            parameters.put("project", filter.projectId());
        }
        if (filter.requestedBy() != null) {
            parameters.put("requestedBy", filter.requestedBy());
        }
        return parameters;
    }

    /** Moves the request between states; false when it is no longer in one of {@code from}. */
    public boolean transition(long id, List<String> from, String to) {
        return jdbc.sql("UPDATE promotion_request SET status = :to WHERE id = :id AND status IN (:from)")
                .param("id", id)
                .param("from", from)
                .param("to", to)
                .update() == 1;
    }

    public boolean decide(long id, String status, String user, String note) {
        return jdbc.sql("""
                        UPDATE promotion_request
                           SET status = :status, decided_by = :user, decided_at = SYSTIMESTAMP, decision_note = :note
                         WHERE id = :id AND status = 'PENDING_APPROVAL'
                        """)
                .param("id", id)
                .param("status", status)
                .param("user", user)
                .param("note", Rows.clob(note))
                .update() == 1;
    }

    public void probed(long id, String result, JsonNode detail) {
        jdbc.sql("""
                        UPDATE promotion_request SET probe_status = :result, probe_detail = :detail
                         WHERE id = :id
                        """)
                .param("id", id)
                .param("result", result)
                .param("detail", Rows.clob(detail))
                .update();
    }

    public void applied(long id, long configVersion) {
        jdbc.sql("""
                        UPDATE promotion_request
                           SET status = 'APPLIED', config_version = :version, applied_at = SYSTIMESTAMP, failure_reason = NULL
                         WHERE id = :id
                        """)
                .param("id", id)
                .param("version", configVersion)
                .update();
    }

    public void failed(long id, String reason) {
        jdbc.sql("UPDATE promotion_request SET status = 'FAILED', failure_reason = :reason WHERE id = :id")
                .param("id", id)
                .param("reason", Rows.clob(reason))
                .update();
    }

    /** The environment may have applied it; the request stays APPLYING so RETRY can settle it. */
    public void unanswered(long id, String reason) {
        jdbc.sql("UPDATE promotion_request SET failure_reason = :reason WHERE id = :id")
                .param("id", id)
                .param("reason", Rows.clob(reason))
                .update();
    }

    public Optional<Long> open(SubjectType type, long subjectId, String targetEnv) {
        return jdbc.sql("""
                        SELECT id FROM promotion_request
                         WHERE %s = :subject AND target_env = :target
                           AND status IN ('PENDING_APPROVAL', 'APPROVED', 'APPLYING')
                        """.formatted(type.idColumn()))
                .param("subject", subjectId)
                .param("target", targetEnv)
                .query(Long.class).optional();
    }

    public boolean previouslyApplied(SubjectType type, long subjectId, int version, String targetEnv) {
        return jdbc.sql("""
                        SELECT count(*) FROM promotion_request
                         WHERE %s = :subject AND %s = :version AND target_env = :target AND status = 'APPLIED'
                        """.formatted(type.idColumn(), type.versionColumn()))
                .param("subject", subjectId)
                .param("version", version)
                .param("target", targetEnv)
                .query(Long.class).single() > 0;
    }
}
