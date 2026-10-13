package com.acme.broker.admin.db;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

import com.acme.broker.core.json.Json;

import tools.jackson.databind.JsonNode;

/** Column reading and parameter helpers shared by the repositories. */
public final class Rows {

    /** SQL expression that turns a JSON array parameter into {@code TEXT[]}. */
    public static final String TEXT_ARRAY = "ARRAY(SELECT jsonb_array_elements_text(CAST(%s AS jsonb)))";

    private Rows() {
    }

    public static String textArray(String parameter) {
        return TEXT_ARRAY.formatted(":" + parameter);
    }

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime time = rs.getObject(column, OffsetDateTime.class);
        return time == null ? null : time.toInstant();
    }

    public static JsonNode json(ResultSet rs, String column) throws SQLException {
        String text = rs.getString(column);
        return text == null ? null : Json.parse(text);
    }

    public static List<String> strings(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

    public static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public static String jsonList(List<String> values) {
        return Json.write(values == null ? List.of() : values);
    }
}
