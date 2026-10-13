package com.acme.broker.core.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.acme.broker.core.json.Json;

import tools.jackson.databind.JsonNode;

class LoggingTest {

    @Test
    void passwordIsMaskedAtEveryDepthIgnoringCase() {
        JsonNode masked = Masker.mask(Json.parse("""
                {"user": "ali", "password": "123", "nested": {"Password": "x", "list": [{"PASSWORD": 1}]},
                 "cardNo": "4111", "empty": null}
                """), List.of("password", "cardNo"));
        assertThat(masked).isEqualTo(Json.parse("""
                {"user": "ali", "password": "xxxx", "nested": {"Password": "xxxx", "list": [{"PASSWORD": "xxxx"}]},
                 "cardNo": "xxxx", "empty": null}
                """));
    }

    @Test
    void captureCutsLargeBodiesOnCharacterBoundaries() {
        Masker.Capture small = Masker.capture(Json.parse("{\"a\": 1}"), 100);
        assertThat(small.truncated()).isFalse();
        assertThat(small.size()).isEqualTo(7);

        Masker.Capture large = Masker.capture(Json.string("ççççç"), 5);
        assertThat(large.truncated()).isTrue();
        assertThat(large.size()).isEqualTo(10);
        assertThat(Json.text(large.body())).isEqualTo("çç");
    }

    @Test
    void eventsRoundTripInSnakeCase() {
        LogEvent event = new LogEvent("0192f1c4-7a3b-7c1e-9d2f-5b8e4a1c3d20", "4bf92f3577b34da6a3ce929d0e0e4736",
                LogEvent.EventType.RESPONSE, Instant.parse("2026-10-08T11:28:41.512Z"), "PROD", "order-service",
                "create_order", 3, "order-service", 2, "web", "u12345", "u12345", null,
                List.of("ORDER_READ", "ORDER_WRITE"), "3f2b8c1e", null, "10.20.30.40", "3f1c9a7b", "POST", "/orders",
                201, "SUCCESS", 87L, null, 412, false,
                Json.parse("{\"BACKEND_STATUS\": 201, \"status\": 200, \"data\": \"\"}"), null, "broker-7f9c-2");

        JsonNode json = EventCodec.toJson(event);
        assertThat(Json.text(json.get("event_time"))).isEqualTo("2026-10-08T11:28:41.512Z");
        assertThat(json.get("perm_list").size()).isEqualTo(2);
        assertThat(json.get("route_version").intValue()).isEqualTo(3);
        assertThat(json.has("routeVersion")).isFalse();

        LogEvent back = EventCodec.fromBytes(EventCodec.toBytes(event));
        assertThat(back).isEqualTo(event);
    }
}
