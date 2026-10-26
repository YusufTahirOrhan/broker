package com.acme.yapi.sink.clickhouse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.logging.LogEvent;
import com.acme.yapi.sink.fake.Events;

import tools.jackson.databind.node.ObjectNode;

class ClickHouseWriterTest {

    @Test
    void rowsFollowTheTableAndHaveNoNullsOrToken() {
        LogEvent event = Events.request(Events.newRequestId(), "order-service");
        ObjectNode row = ClickHouseWriter.row(event);

        assertThat(Json.fieldNames(row)).containsExactly("event_time", "request_id", "trace_id", "event_type",
                "environment", "maintask", "subtask", "route_version", "response_profile", "response_profile_version",
                "user_id", "client_ip", "user_agent", "application", "uid", "http_method", "target_path",
                "status_code", "outcome", "duration_ms", "body", "body_size", "body_truncated", "error_message",
                "yapi_instance");
        row.forEachEntry((name, value) -> assertThat(value.isNull()).as(name).isFalse());
        assertThat(row.get("event_time").stringValue()).isEqualTo("2026-10-08T11:28:41.425Z");
        assertThat(row.get("body").isString()).isTrue();
    }
}
