package com.acme.yapi.sink.fake;

import java.time.Instant;
import java.util.List;

import com.acme.yapi.core.ids.Ids;
import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.logging.LogEvent;

/** Events like the broker writes them, after the design document's examples. */
public final class Events {

    private Events() {
    }

    public static String newRequestId() {
        return Ids.uuidV7().toString();
    }

    public static LogEvent request(String requestId, String maintask) {
        return new LogEvent(requestId, "4bf92f3577b34da6a3ce929d0e0e4736", LogEvent.EventType.REQUEST,
                Instant.parse("2026-10-08T11:28:41.425Z"), "PROD", maintask, "create_order", 3, "order-service", 2,
                "web", "u12345", "u12345", "b1f4c2d8-6e3a-4f5b-9c7d-2a8e0f1b3c4d", List.of("ORDER_READ", "ORDER_WRITE"),
                "3f2b8c1e-9a4d-4e7b-b6c2-5d1e8f0a9c47", null, "10.20.30.40", "agent-hash", "POST", "/orders", null,
                null, null, Json.parse("""
                        {"maintask": "%s", "subtask": "create_order", "application": "web", "uid": "u12345",
                         "password": "xxxx", "adres": {"il": "Ankara", "ilce": "Çankaya"}}
                        """.formatted(maintask)), 160, false, null, null, "yapi-7f9c-2", null, null);
    }

    public static LogEvent response(String requestId) {
        return new LogEvent(requestId, "4bf92f3577b34da6a3ce929d0e0e4736", LogEvent.EventType.RESPONSE,
                Instant.parse("2026-10-08T11:28:41.512Z"), "PROD", "order-service", "create_order", 3,
                "order-service", 2, "web", "u12345", "u12345", "b1f4c2d8-6e3a-4f5b-9c7d-2a8e0f1b3c4d",
                List.of("ORDER_READ", "ORDER_WRITE"), "3f2b8c1e-9a4d-4e7b-b6c2-5d1e8f0a9c47", null, "10.20.30.40",
                "agent-hash", "POST", "/orders", 201, "SUCCESS", 87L, null, 412, false,
                Json.parse("{\"BACKEND_STATUS\": 201, \"status\": 200, \"data\": \"\"}"), null, "yapi-7f9c-2", null, null);
    }

    /** The response of a request that the alarm check stopped: the legacy body and the alarms found. */
    public static LogEvent alarmed(String requestId) {
        return new LogEvent(requestId, "4bf92f3577b34da6a3ce929d0e0e4736", LogEvent.EventType.RESPONSE,
                Instant.parse("2026-10-08T11:28:41.512Z"), "PROD", "order-service", "create_order", 3,
                null, null, "web", "u12345", "u12345", "b1f4c2d8-6e3a-4f5b-9c7d-2a8e0f1b3c4d",
                List.of("ORDER_READ", "ORDER_WRITE"), "3f2b8c1e-9a4d-4e7b-b6c2-5d1e8f0a9c47", null, "10.20.30.40",
                "agent-hash", "POST", "/orders", 200, "ALARM", 12L, null, null, null,
                Json.parse("{\"response\": false, \"badprocess\": true, \"status\": 500}"), null, "yapi-7f9c-2",
                Json.parse("""
                        {"maintask": "guvenlik", "subtask": "alarm_kontrol", "alarms": ["10000000146"],
                         "params": {"maintask": "order-service", "subtask": "create_order", "uid": "u12345"}}
                        """), null);
    }

    /** The response of a request refused for lack of permission; the service was not called. */
    public static LogEvent denied(String requestId) {
        return new LogEvent(requestId, "4bf92f3577b34da6a3ce929d0e0e4736", LogEvent.EventType.RESPONSE,
                Instant.parse("2026-10-08T11:28:41.512Z"), "PROD", "order-service", "create_order", 3,
                null, null, "web", "u12345", "u12345", "b1f4c2d8-6e3a-4f5b-9c7d-2a8e0f1b3c4d",
                List.of("CRM_READ"), "3f2b8c1e-9a4d-4e7b-b6c2-5d1e8f0a9c47", null, "10.20.30.40",
                "agent-hash", "POST", "/orders", 403, "DENIED", 3L, null, null, null,
                Json.parse("{\"result\": false, \"status\": 403}"), null, "yapi-7f9c-2", null,
                new LogEvent.Denial(LogEvent.DeniedReason.PERMISSION, List.of("ORDER_WRITE"), false, Json.parse("""
                        {"maintask": "order-service", "subtask": "create_order", "application": "web",
                         "uid": "u12345", "password": "xxxx", "adres": {"il": "Ankara"}}
                        """)));
    }
}
