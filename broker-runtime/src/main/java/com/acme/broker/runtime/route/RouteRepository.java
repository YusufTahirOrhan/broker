package com.acme.broker.runtime.route;

import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.acme.broker.core.json.Json;
import com.acme.broker.runtime.metrics.BrokerMetrics;

/**
 * Reads the runtime DB. The version, profiles and routes come from one REPEATABLE READ snapshot, so a broker never
 * serves routes of one version with profiles of another.
 */
@Repository
public class RouteRepository {

    private static final Logger log = LoggerFactory.getLogger(RouteRepository.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate snapshotTransaction;
    private final BrokerMetrics metrics;

    public RouteRepository(JdbcClient jdbc, PlatformTransactionManager transactions, BrokerMetrics metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.snapshotTransaction = new TransactionTemplate(transactions);
        snapshotTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshotTransaction.setReadOnly(true);
    }

    public long currentVersion() {
        return jdbc.sql("SELECT version FROM broker_runtime.runtime_config_version WHERE id = 1")
                .query(Long.class)
                .single();
    }

    private record ProfileRow(String name, int version, String definition) {
    }

    private record RouteRow(String maintask, String subtask, int version, String httpMethod, String pathTemplate,
                            List<String> permissions, boolean anonymous, String responseProfile, String definition,
                            String baseUrl) {
    }

    /**
     * Loads everything active. A row that cannot be parsed is skipped with an alarm, and so are the routes of a
     * broken profile; the rest still load.
     */
    public RouteSnapshot loadSnapshot() {
        return snapshotTransaction.execute(status -> {
            long version = currentVersion();
            List<ProfileRow> profileRows = jdbc.sql("""
                            SELECT name, version, definition::text AS definition
                              FROM broker_runtime.runtime_response_profile
                            """)
                    .query((rs, row) -> new ProfileRow(rs.getString("name"), rs.getInt("version"),
                            rs.getString("definition")))
                    .list();
            List<RouteRow> routeRows = jdbc.sql("""
                            SELECT r.maintask, r.subtask, r.version, r.http_method, r.path_template, r.permissions,
                                   r.anonymous, r.response_profile, r.definition::text AS definition, p.base_url
                              FROM broker_runtime.runtime_route r
                              JOIN broker_runtime.runtime_project p ON p.maintask = r.maintask
                             WHERE r.status = 'ACTIVE' AND p.enabled
                            """)
                    .query((rs, row) -> new RouteRow(rs.getString("maintask"), rs.getString("subtask"),
                            rs.getInt("version"), rs.getString("http_method"), rs.getString("path_template"),
                            strings(rs.getArray("permissions")), rs.getBoolean("anonymous"),
                            rs.getString("response_profile"), rs.getString("definition"), rs.getString("base_url")))
                    .list();
            List<RouteSnapshot.Project> projects = jdbc.sql("""
                            SELECT maintask, base_url, enabled FROM broker_runtime.runtime_project
                            """)
                    .query((rs, row) -> new RouteSnapshot.Project(rs.getString("maintask"), rs.getString("base_url"),
                            rs.getBoolean("enabled")))
                    .list();

            Map<String, CompiledProfile> profiles = new HashMap<>();
            for (ProfileRow row : profileRows) {
                switch (RouteCompiler.profile(row.name(), row.version(), Json.tryParse(row.definition()))) {
                    case RouteCompiler.Compiled.Ok<CompiledProfile> ok -> profiles.put(row.name(), ok.value());
                    case RouteCompiler.Compiled.Broken<CompiledProfile> broken -> skip("profile", row.name(),
                            broken.reason());
                }
            }
            List<Route> routes = new ArrayList<>();
            for (RouteRow row : routeRows) {
                String name = row.maintask() + "/" + row.subtask();
                CompiledProfile profile = null;
                if (row.responseProfile() != null) {
                    profile = profiles.get(row.responseProfile());
                    if (profile == null) {
                        skip("route", name, "yanıt profili yüklenemedi: " + row.responseProfile());
                        continue;
                    }
                }
                switch (RouteCompiler.route(row.maintask(), row.subtask(), row.version(), row.httpMethod(),
                        row.pathTemplate(), row.permissions(), row.anonymous(), profile,
                        Json.tryParse(row.definition()), row.baseUrl(), false)) {
                    case RouteCompiler.Compiled.Ok<Route> ok -> routes.add(ok.value());
                    case RouteCompiler.Compiled.Broken<Route> broken -> skip("route", name, broken.reason());
                }
            }
            return RouteSnapshot.of(version, routes, projects, profiles.values());
        });
    }

    private void skip(String kind, String name, String reason) {
        log.error("Bozuk kayıt atlandı: {} {} ({})", kind, name, reason);
        metrics.skipped(kind);
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }
}
