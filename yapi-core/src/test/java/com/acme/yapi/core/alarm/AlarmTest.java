package com.acme.yapi.core.alarm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.request.BackendRequest;
import com.acme.yapi.core.request.BackendRequestBuilder;
import com.acme.yapi.core.request.RequestContext;
import com.acme.yapi.core.route.HttpMethodName;
import com.acme.yapi.core.route.PathTemplate;
import com.acme.yapi.core.route.RouteDefinitionParser;
import com.acme.yapi.core.route.RouteValidator;
import com.acme.yapi.core.validation.FieldError;

import tools.jackson.databind.node.ObjectNode;

class AlarmTest {

    @Test
    void theValuesAreEveryLeafAsTextInOrderWithoutEnvelopeMaskedAndNullFields() {
        ObjectNode body = (ObjectNode) Json.parse("""
                {"maintask": "sorgu", "subtask": "kisi_sorgu", "application": "web", "uid": "12345",
                 "reason": "Müşteri talebi 2026", "tckn": "10000000146", "password": "gizli",
                 "kisi": {"ad": "Ali", "yas": 42, "aktif": true, "adres": {"il": "Ankara", "Kart": "4111"}},
                 "liste": ["a", 7, 1.5, null, {"x": "y"}], "bos": null}
                """);

        assertThat(AlarmValues.of(body, List.of("password", "kart")))
                .containsExactly("Müşteri talebi 2026", "10000000146", "Ali", "42", "true", "Ankara", "a", "7", "1.5",
                        "y");
    }

    @Test
    void theAlarmsAreTheListAtTheResultPath() {
        assertThat(AlarmValues.alarms(Json.parse("[\"10000000146\", 42]"), null)).contains(List.of("10000000146", "42"));
        assertThat(AlarmValues.alarms(Json.parse("[]"), null)).contains(List.of());
        assertThat(AlarmValues.alarms(Json.parse("{\"data\": {\"alarms\": [\"x\"]}}"), "data.alarms"))
                .contains(List.of("x"));
        assertThat(AlarmValues.alarms(Json.parse("{\"data\": {\"alarms\": \"x\"}}"), "data.alarms")).isEmpty();
        assertThat(AlarmValues.alarms(Json.parse("{\"data\": []}"), "data.alarms")).isEmpty();
        assertThat(AlarmValues.alarms(Json.parse("{\"ok\": true}"), null)).isEmpty();
        assertThat(AlarmValues.alarms(null, null)).isEmpty();
    }

    @Test
    void anAlarmServiceRouteSendsTheValuesWithTheUsualPlaceholders() {
        String definition = """
                {"alarmService": {"resultPath": "alarmList"},
                 "request": {"headers": {"X-Clientip": "${header.X-Clientip}"},
                             "body": {"mode": "TEMPLATE", "contentType": "application/json",
                                      "template": {"values": "${alarm.values}", "queryId": "${requestId}",
                                                   "uid": "${request.uid}"}}}}
                """;
        RouteValidator.Context readable = RouteValidator.Context.of(List.of("X-Clientip"));
        assertThat(RouteValidator.validate("POST", "/alarm/check", Json.parse(definition), readable)).isEmpty();

        ObjectNode body = (ObjectNode) Json.parse("{\"maintask\": \"sorgu\", \"uid\": \"12345\", \"tckn\": \"1\"}");
        RequestContext context = new RequestContext(body, body, Map.of("X-Clientip", "10.0.0.5")::get, readable,
                "12345", null, "req-1").withAlarmValues(List.of("1"));
        BackendRequest request = BackendRequestBuilder.build(HttpMethodName.POST, PathTemplate.parse("/alarm/check"),
                RouteDefinitionParser.parse(Json.parse(definition)).definition(), "http://alarm.svc", context);

        assertThat(request.previewBody()).isEqualTo(Json.parse("""
                {"values": ["1"], "queryId": "req-1", "uid": "12345"}
                """));
        assertThat(request.headers()).containsEntry("X-Clientip", List.of("10.0.0.5"));
    }

    @Test
    void theAlarmRulesOfADefinition() {
        RouteValidator.Context any = RouteValidator.Context.anyHeader();

        assertThat(RouteValidator.validate("GET", "/a", Json.parse("""
                {"alarm": {"check": "ROUTE", "maintask": "guvenlik", "subtask": "alarm_kontrol"}}
                """), any)).isEmpty();
        assertThat(RouteValidator.validate("GET", "/a", Json.parse("{\"alarm\": {\"check\": \"OFF\"}}"), any)).isEmpty();
        assertThat(fields("{\"alarm\": {\"check\": \"ROUTE\"}}"))
                .containsExactly("definition.alarm.maintask", "definition.alarm.subtask");
        assertThat(fields("{\"alarm\": {\"check\": \"INHERIT\", \"maintask\": \"guvenlik\"}}"))
                .containsExactly("definition.alarm.check");
        assertThat(fields("{\"alarm\": {\"check\": \"SOMETIMES\"}}")).containsExactly("definition.alarm.check");
        assertThat(fields("{\"alarm\": {\"check\": \"OFF\", \"extra\": 1}}")).containsExactly("definition.alarm.extra");
        assertThat(fields("""
                {"alarmService": {}, "alarm": {"check": "ROUTE", "maintask": "guvenlik", "subtask": "alarm_kontrol"}}
                """)).containsExactly("definition.alarm.check");
        assertThat(fields("{\"alarmService\": {\"resultPath\": \"data..alarms\"}}"))
                .containsExactly("definition.alarmService.resultPath");
        assertThat(fields("""
                {"request": {"body": {"mode": "TEMPLATE", "template": {"v": "${alarm.values}"}}}}
                """)).containsExactly("definition.request.body.template.v");
        assertThat(fields("""
                {"alarmService": {}, "request": {"body": {"mode": "TEMPLATE", "template": {"v": "${alarm.other}"}}}}
                """)).containsExactly("definition.request.body.template.v");
    }

    @Test
    void onlyAnObjectMakesAnAlarmService() {
        assertThat(RouteDefinitionParser.isAlarmService(Json.parse("{\"alarmService\": {}}"))).isTrue();
        assertThat(RouteDefinitionParser.isAlarmService(Json.parse("{\"alarmService\": null}"))).isFalse();
        assertThat(RouteDefinitionParser.isAlarmService(Json.parse("{}"))).isFalse();
        assertThat(RouteDefinitionParser.isAlarmService(null)).isFalse();
        assertThat(RouteDefinitionParser.parse(Json.parse("{\"alarmService\": null}")).definition().isAlarmService())
                .isFalse();
    }

    private static List<String> fields(String definition) {
        return RouteValidator.validate("POST", "/a", Json.parse(definition), RouteValidator.Context.anyHeader())
                .stream().map(FieldError::field).toList();
    }
}
