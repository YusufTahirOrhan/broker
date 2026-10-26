package com.acme.yapi.runtime.route;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.route.AlarmRoute;
import com.acme.yapi.runtime.metrics.YapiMetrics;

/**
 * Reads the runtime DB. The version, profiles and routes come from one read-only transaction: Oracle answers every
 * query of a {@code SET TRANSACTION READ ONLY} transaction as of its start, so a broker never serves routes of one
 * version with profiles of another. (Oracle has no REPEATABLE READ; SERIALIZABLE would do the same with more locking.)
 */
@Repository
public class RouteRepository {

    private static final Logger log = LoggerFactory.getLogger(RouteRepository.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate snapshotTransaction;
    private final YapiMetrics metrics;

    public RouteRepository(JdbcClient jdbc, DataSource dataSource, YapiMetrics metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(dataSource);
        transactions.setEnforceReadOnly(true);
        this.snapshotTransaction = new TransactionTemplate(transactions);
        snapshotTransaction.setReadOnly(true);
    }

    public long currentVersion() {
        return jdbc.sql("SELECT version FROM runtime_config_version WHERE id = 1")
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
        return inSnapshot(() -> {
            long version = currentVersion();
            List<ProfileRow> profileRows = jdbc.sql("""
                            SELECT name, version, definition
                              FROM runtime_response_profile
                            """)
                    .query((rs, row) -> new ProfileRow(rs.getString("name"), rs.getInt("version"),
                            rs.getString("definition")))
                    .list();
            List<RouteRow> routeRows = jdbc.sql("""
                            SELECT r.maintask, r.subtask, r.version, r.http_method, r.path_template, r.permissions,
                                   r.anonymous, r.response_profile, r.definition, p.base_url
                              FROM runtime_route r
                              JOIN runtime_project p ON p.maintask = r.maintask
                             WHERE r.status = 'ACTIVE' AND p.enabled = 1
                            """)
                    .query((rs, row) -> new RouteRow(rs.getString("maintask"), rs.getString("subtask"),
                            rs.getInt("version"), rs.getString("http_method"), rs.getString("path_template"),
                            Json.stringList(rs.getString("permissions")), rs.getBoolean("anonymous"),
                            rs.getString("response_profile"), rs.getString("definition"), rs.getString("base_url")))
                    .list();
            List<RouteSnapshot.Project> projects = jdbc.sql("""
                            SELECT maintask, base_url, enabled, alarm_maintask, alarm_subtask FROM runtime_project
                            """)
                    .query((rs, row) -> new RouteSnapshot.Project(rs.getString("maintask"), rs.getString("base_url"),
                            rs.getBoolean("enabled"), rs.getString("alarm_maintask") == null ? null
                            : new AlarmRoute(rs.getString("alarm_maintask"), rs.getString("alarm_subtask"))))
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

    /** Runs the reads in one read-only transaction: every query sees the database as of the transaction's start. */
    public <T> T inSnapshot(Supplier<T> reads) {
        return snapshotTransaction.execute(status -> reads.get());
    }

    private void skip(String kind, String name, String reason) {
        log.error("Bozuk kayıt atlandı: {} {} ({})", kind, name, reason);
        metrics.skipped(kind);
    }
}
