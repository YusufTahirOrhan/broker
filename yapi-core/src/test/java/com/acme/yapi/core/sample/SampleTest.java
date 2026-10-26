package com.acme.yapi.core.sample;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.spec.OpenApiSpec;
import com.acme.yapi.core.testing.CoreFixtures;

import tools.jackson.databind.JsonNode;

class SampleTest {

    private static final String BASH_CURL = """
            curl 'https://app.example.com/yapi' \\
              -H 'Accept: application/json' \\
              -H 'X-Token: 3f2b8c1e' \\
              -H 'Cookie: JSESSIONID=1' \\
              -H 'content-type: application/json' \\
              --data-raw $'{"maintask":"order-service","subtask":"add_order_note","uid":"u1","siparisNo":981,"notMetni":"Kap\\u0131ya","adres":{"il":"Ankara"},"password":"gizli"}' \\
              --compressed
            """;

    @Test
    void bashCurlDropsCredentials() {
        SampleParser.Sample sample = SampleParser.parse(BASH_CURL);
        assertThat(sample.method()).isEqualTo("POST");
        assertThat(sample.url()).isEqualTo("https://app.example.com/yapi");
        assertThat(sample.headers()).containsOnlyKeys("Accept", "content-type");
        assertThat(sample.removedHeaders()).containsExactly("X-Token", "Cookie");
        assertThat(Json.text(sample.body().get("notMetni"))).isEqualTo("Kapıya");
    }

    @Test
    void cmdCurlIsUnescaped() {
        String cmd = """
                curl "https://app.example.com/yapi" ^
                  -H "X-Token: abc" ^
                  --data-raw "^{^\\^"maintask^\\^":^\\^"m^\\^",^\\^"a^\\^":1^}"
                """;
        SampleParser.Sample sample = SampleParser.parse(cmd);
        assertThat(sample.removedHeaders()).containsExactly("X-Token");
        assertThat(sample.body()).isEqualTo(Json.parse("{\"maintask\": \"m\", \"a\": 1}"));
    }

    @Test
    void plainJsonIsABody() {
        SampleParser.Sample sample = SampleParser.parse("  {\"maintask\": \"m\", \"x\": [1]}  ");
        assertThat(sample.method()).isNull();
        assertThat(sample.body().get("x").isArray()).isTrue();
        assertThatThrownBy(() -> SampleParser.parse("merhaba")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void draftInfersFieldsFromValues() {
        SampleTemplate.Result result = SampleTemplate.draft(BASH_CURL, null);
        assertThat(result.maintask()).isEqualTo("order-service");
        assertThat(result.subtask()).isEqualTo("add_order_note");
        assertThat(result.fields()).isEqualTo(Json.parse("""
                [{"name": "siparisNo", "type": "integer", "required": true},
                 {"name": "notMetni", "type": "string", "required": true},
                 {"name": "adres.il", "type": "string", "required": true},
                 {"name": "password", "type": "string", "required": true}]
                """));
        assertThat(Json.text(result.maskedSample().get("password"))).isEqualTo("xxxx");
        assertThat(result.removedHeaders()).containsExactly("X-Token", "Cookie");
        assertThat(result.proposal()).isNull();
    }

    @Test
    void draftMapsFieldsToTheChosenOperationByName() {
        Map<String, OpenApiSpec.Operation> operations = CoreFixtures.operationsByKey(CoreFixtures.sampleSpec());
        SampleTemplate.Result result = SampleTemplate.draft("""
                {"maintask": "order-service", "subtask": "add_order_note", "siparisNo": 981, "text": "x",
                 "author_id": "u1", "adres": {"il": "Ankara"}}
                """, operations.get("POST /orders/{id}/notes"));

        JsonNode request = result.proposal().get("definition").get("request");
        assertThat(request.get("body").get("template")).isEqualTo(Json.parse("""
                {"text": "${request.text}", "authorId": "${request.author_id}"}
                """));
        assertThat(request.has("pathVariables")).isFalse();
        assertThat(result.unmatchedFields()).containsExactly("siparisNo", "adres.il");
        assertThat(result.unmatchedServiceParameters()).extracting(parameter -> Json.text(parameter.get("name")))
                .containsExactly("id");
    }

    @Test
    void namesMatchIgnoringCaseAndSeparators() {
        assertThat(SampleTemplate.match("authorId", Set.of("author_id", "x")))
                .isEqualTo("author_id");
        assertThat(SampleTemplate.match("il", java.util.Set.of("adres.il"))).isEqualTo("adres.il");
        assertThat(SampleTemplate.match("yok", java.util.Set.of("adres.il"))).isNull();
    }
}
