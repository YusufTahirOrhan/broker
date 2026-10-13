package com.acme.broker.admin.environment;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.acme.broker.admin.web.ApiException;

@Repository
public class EnvironmentRepository {

    private final JdbcClient jdbc;

    public EnvironmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Environment> all() {
        return jdbc.sql("""
                        SELECT code, name, sort_order, admin_api_url, requires_approval, test_calls_allowed
                          FROM environment ORDER BY sort_order
                        """)
                .query((rs, row) -> new Environment(rs.getString("code"), rs.getString("name"), rs.getInt("sort_order"),
                        rs.getString("admin_api_url"), rs.getBoolean("requires_approval"),
                        rs.getBoolean("test_calls_allowed")))
                .list();
    }

    public Optional<Environment> find(String code) {
        return all().stream().filter(environment -> environment.code().equals(code)).findFirst();
    }

    public Environment require(String code) {
        return find(code).orElseThrow(() -> ApiException.notFound("Ortam yok: " + code));
    }

    public Environment first() {
        return all().getFirst();
    }

    /** The environment a version must be active in before it can move to {@code target}; empty for the first one. */
    public Optional<Environment> previous(Environment target) {
        return all().stream().filter(environment -> environment.sortOrder() == target.sortOrder() - 1).findFirst();
    }
}
