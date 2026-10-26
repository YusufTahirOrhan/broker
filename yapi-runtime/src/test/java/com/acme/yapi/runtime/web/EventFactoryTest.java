package com.acme.yapi.runtime.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.route.LoggingSpec;

/** {@code client_response} under the route's log policy ("Yanıt verisi loglansın"). */
class EventFactoryTest {

    private static final LoggingSpec OFF = new LoggingSpec(true, false, List.of("tckn"), 65_536, null);
    private static final LoggingSpec ON = new LoggingSpec(true, true, List.of("tckn"), 65_536, null);

    private static LoggingSpec on(int maxBodyBytes) {
        return new LoggingSpec(true, true, List.of("tckn"), maxBodyBytes, null);
    }

    private static final String PROFILED = """
            {"BACKEND_STATUS": 200, "status": 200, "data": {"tckn": "12345678901", "ad": "Ayşe", "password": "p"}}
            """;

    @Test
    void aProfiledBodyLogsNoDataUnlessTheRouteAsks() {
        EventFactory.ClientLog off = EventFactory.clientResponse(Json.parse(PROFILED), OFF);
        assertThat(off.value()).isEqualTo(Json.parse("{\"BACKEND_STATUS\": 200, \"status\": 200, \"data\": \"\"}"));
        assertThat(off.truncated()).isFalse();

        EventFactory.ClientLog on = EventFactory.clientResponse(Json.parse(PROFILED), ON);
        assertThat(on.value()).isEqualTo(Json.parse("""
                {"BACKEND_STATUS": 200, "status": 200,
                 "data": "{\\"tckn\\":\\"xxxx\\",\\"ad\\":\\"Ayşe\\",\\"password\\":\\"xxxx\\"}"}
                """));
        assertThat(on.truncated()).isFalse();
    }

    @Test
    void aProfiledDataTextIsCutOnACharacterBoundary() {
        // {"tckn":"xxxx","ad":"Ay is 23 bytes and ş takes two: a 24-byte limit falls inside it
        EventFactory.ClientLog cut = EventFactory.clientResponse(Json.parse(PROFILED), on(24));

        assertThat(Json.text(cut.value().get("data"))).isEqualTo("{\"tckn\":\"xxxx\",\"ad\":\"Ay");
        assertThat(cut.truncated()).isTrue();
        assertThat(cut.value().get("status").intValue()).isEqualTo(200);
    }

    @Test
    void aProfiledStringDataStaysAsItIsAndABodyWithoutDataIsUnchanged() {
        EventFactory.ClientLog text = EventFactory.clientResponse(
                Json.parse("{\"status\": 200, \"data\": \"hazır\"}"), ON);
        assertThat(text.value()).isEqualTo(Json.parse("{\"status\": 200, \"data\": \"hazır\"}"));

        EventFactory.ClientLog noData = EventFactory.clientResponse(
                Json.parse("{\"status\": 404, \"message\": \"yok\"}"), ON);
        assertThat(noData.value()).isEqualTo(Json.parse("{\"status\": 404, \"message\": \"yok\"}"));
    }

    @Test
    void aPassedThroughBodyIsOnlyItsStatusUnlessTheRouteAsks() {
        String body = "{\"ok\": true, \"data\": [{\"tckn\": \"1\"}], \"tckn\": \"2\"}";

        assertThat(EventFactory.passedThrough(201, Json.parse(body), OFF).value())
                .isEqualTo(Json.parse("{\"status\": 201}"));
        EventFactory.ClientLog on = EventFactory.passedThrough(201, Json.parse(body), ON);
        assertThat(on.value()).isEqualTo(Json.parse("""
                {"ok": true, "data": "[{\\"tckn\\":\\"xxxx\\"}]", "tckn": "xxxx"}
                """));
        assertThat(on.truncated()).isFalse();
    }

    @Test
    void aPassedThroughBodyThatIsNotAnObjectGoesIntoData() {
        assertThat(EventFactory.passedThrough(200, Json.parse("[1, 2]"), ON).value())
                .isEqualTo(Json.parse("{\"status\": 200, \"data\": \"[1,2]\"}"));
        assertThat(EventFactory.passedThrough(200, Json.string("düz metin"), ON).value())
                .isEqualTo(Json.parse("{\"status\": 200, \"data\": \"düz metin\"}"));
        assertThat(EventFactory.passedThrough(200, null, ON).value()).isEqualTo(Json.parse("{\"status\": 200}"));
    }

    @Test
    void aPassedThroughBodyOverTheLimitBecomesStatusAndCutText() {
        EventFactory.ClientLog cut = EventFactory.passedThrough(200,
                Json.parse("{\"ad\": \"Çağrı\", \"tckn\": \"12345678901\"}"), on(10));

        assertThat(cut.value()).isEqualTo(Json.parse("{\"status\": 200, \"data\": \"{\\\"ad\\\":\\\"Ça\"}"));
        assertThat(cut.truncated()).isTrue();
    }
}
