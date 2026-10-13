package com.acme.broker.core.diff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.acme.broker.core.json.Json;

class JsonDiffTest {

    @Test
    void reportsChangedAddedAndRemovedFields() {
        List<JsonDiff.Change> changes = JsonDiff.diff(Json.parse("""
                {"timeoutMs": 5000, "permissions": ["A"], "request": {"fields": [{"name": "a"}, {"name": "b"}]},
                 "old": 1}
                """), Json.parse("""
                {"timeoutMs": 8000, "permissions": ["A", "B"], "request": {"fields": [{"name": "a"}]}, "new": true}
                """));
        assertThat(changes).extracting(JsonDiff.Change::path).containsExactly(
                "new", "old", "permissions", "request.fields[1]", "timeoutMs");
        assertThat(changes.get(0).before()).isNull();
        assertThat(changes.get(1).after()).isNull();
    }

    @Test
    void equalDocumentsHaveNoChanges() {
        assertThat(JsonDiff.diff(Json.parse("{\"a\": [1, 2]}"), Json.parse("{\"a\": [1, 2]}"))).isEmpty();
    }
}
