package com.acme.yapi.core.testing;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;

import org.testcontainers.containers.OracleContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Oracle XE in a container, standing in for the Oracle 19c of the environments. One container per module run: the
 * schema owner is the container's application user, the other users only get {@code CREATE SESSION}, so what they may
 * do comes from the grants of the migration, as in an environment.
 *
 * <p>The image comes from the GitHub registry; Docker Hub rate-limits anonymous pulls of this large image.
 */
public final class OracleDatabase {

    public static final String IMAGE = "ghcr.io/gvenzl/oracle-xe:21-slim-faststart";
    public static final String PASSWORD = "yapi";

    private final OracleContainer container;
    private final String owner;
    private final List<String> users;

    private OracleDatabase(String owner, List<String> users) {
        this.owner = owner.toUpperCase(Locale.ROOT);
        this.users = users;
        this.container = new OracleContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("gvenzl/oracle-xe"))
                .withUsername(owner)
                .withPassword(PASSWORD);
    }

    /** A database whose schema {@code owner} holds the tables and whose {@code users} connect to it. */
    public static OracleDatabase of(String owner, String... users) {
        return new OracleDatabase(owner, List.of(users));
    }

    public OracleContainer container() {
        return container;
    }

    /** Starts the container when it is not running yet and creates the users. */
    public OracleDatabase start() {
        container.start();
        return createUsers();
    }

    /** Creates the users once the container has been started elsewhere (for example in parallel with others). */
    public OracleDatabase createUsers() {
        try (Connection connection = DriverManager.getConnection(url(), "system", PASSWORD);
             Statement statement = connection.createStatement()) {
            for (String user : users) {
                statement.execute("CREATE USER " + user + " IDENTIFIED BY \"" + PASSWORD + "\"");
                statement.execute("GRANT CREATE SESSION TO " + user);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Oracle kullanıcıları oluşturulamadı", e);
        }
        return this;
    }

    /**
     * Table and column names of the schema that Oracle reserves or treats specially ({@code V$RESERVED_WORDS}); a
     * schema should not use any of them, even quoted.
     */
    public List<String> reservedNames() {
        String query = """
                SELECT o.object_name || CASE WHEN c.column_name IS NULL THEN '' ELSE '.' || c.column_name END AS name
                  FROM all_objects o
                  LEFT JOIN all_tab_columns c ON c.owner = o.owner AND c.table_name = o.object_name
                  JOIN v$reserved_words w ON w.keyword IN (o.object_name, c.column_name)
                 WHERE o.owner = ? AND o.object_type = 'TABLE'
                   AND (w.reserved = 'Y' OR w.res_type = 'Y' OR w.res_attr = 'Y' OR w.res_semi = 'Y')
                """;
        List<String> names = new java.util.ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url(), "system", PASSWORD);
             java.sql.PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, owner);
            try (java.sql.ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    names.add(rows.getString("name"));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Ayrılmış kelimeler okunamadı", e);
        }
        return names;
    }

    public String url() {
        return container.getJdbcUrl();
    }

    /** The schema name, as {@code CURRENT_SCHEMA} expects it. */
    public String schema() {
        return owner;
    }

    public String owner() {
        return owner;
    }
}
