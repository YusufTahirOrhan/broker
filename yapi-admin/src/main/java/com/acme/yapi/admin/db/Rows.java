package com.acme.yapi.admin.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.IntSupplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.support.KeyHolder;

import com.acme.yapi.core.json.Json;

import tools.jackson.databind.JsonNode;

/** Column reading and parameter helpers shared by the repositories (Oracle). */
public final class Rows {

    private Rows() {
    }

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime time = rs.getObject(column, OffsetDateTime.class);
        return time == null ? null : time.toInstant();
    }

    public static JsonNode json(ResultSet rs, String column) throws SQLException {
        String text = rs.getString(column);
        return text == null ? null : Json.parse(text);
    }

    /** A JSON array of strings, as {@code JSON_ARRAYAGG} returns it; empty when the aggregate had no rows. */
    public static List<String> strings(ResultSet rs, String column) throws SQLException {
        return Json.stringList(rs.getString(column));
    }

    public static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public static Integer intOrNull(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    /** Oracle 19c has no SQL boolean: flags are {@code NUMBER(1)}. */
    public static int flag(boolean value) {
        return value ? 1 : 0;
    }

    /** JSON and free text can be larger than a VARCHAR2 bind; they are bound as CLOB. */
    public static SqlParameterValue clob(String text) {
        return new SqlParameterValue(Types.CLOB, text);
    }

    public static SqlParameterValue clob(JsonNode json) {
        return clob(json == null ? null : Json.write(json));
    }

    /** A text parameter with its type, for one that may be null and is compared with {@code IS NULL}. */
    public static SqlParameterValue text(String value) {
        return new SqlParameterValue(Types.VARCHAR, value);
    }

    /**
     * A {@code LIKE} pattern that finds {@code query} anywhere, with its wildcards escaped ({@code ESCAPE '\'});
     * null for a blank query.
     */
    public static SqlParameterValue contains(String query) {
        if (query == null || query.isBlank()) {
            return text(null);
        }
        String escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return text("%" + escaped + "%");
    }

    /** The generated identity of an insert made with {@code update(keys, "id")}. */
    public static long key(KeyHolder keys) {
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("Eklenen satırın ID'si dönmedi");
        }
        return key.longValue();
    }

    /**
     * Runs a MERGE once more when a concurrent insert of the same key won the race (ORA-00001): the second run sees
     * the committed row and takes the update branch.
     */
    public static int merge(IntSupplier statement) {
        try {
            return statement.getAsInt();
        } catch (DuplicateKeyException e) {
            return statement.getAsInt();
        }
    }
}
