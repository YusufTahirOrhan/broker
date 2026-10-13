package com.acme.broker.core.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.acme.broker.core.json.Json;

import tools.jackson.databind.JsonNode;

class JsonTemplateTest {

    private final Map<String, JsonNode> values = Map.of(
            "request.siparisNo", Json.number(981),
            "request.notMetni", Json.string("Kapıya bırakın"),
            "request.etiketler", Json.parse("[\"a\",\"b\"]"),
            "request.bos", Json.nullNode(),
            "user.id", Json.string("u12345"));

    private final Resolver resolver = placeholder ->
            values.getOrDefault(placeholder.expression(), Json.missing());

    @Test
    void singlePlaceholderKeepsTheValueType() {
        JsonNode rendered = JsonTemplate.compile(Json.parse("""
                {"id": "${request.siparisNo}", "tags": "${request.etiketler}", "author": "${user.id}"}
                """)).render(resolver, false);
        assertThat(rendered.get("id").isIntegralNumber()).isTrue();
        assertThat(rendered.get("id").intValue()).isEqualTo(981);
        assertThat(rendered.get("tags").isArray()).isTrue();
        assertThat(Json.text(rendered.get("author"))).isEqualTo("u12345");
    }

    @Test
    void textAroundAPlaceholderMakesAString() {
        JsonNode rendered = JsonTemplate.compile(Json.parse("""
                {"ref": "SIP-${request.siparisNo}"}
                """)).render(resolver, false);
        assertThat(rendered.get("ref").isString()).isTrue();
        assertThat(Json.text(rendered.get("ref"))).isEqualTo("SIP-981");
    }

    @Test
    void valuesWithoutAValueAreLeftOut() {
        JsonNode rendered = JsonTemplate.compile(Json.parse("""
                {"a": "${request.yok}", "b": "x-${request.yok}", "c": "${request.bos}", "d": ["${request.yok}", 1],
                 "e": "sabit"}
                """)).render(resolver, false);
        assertThat(Json.fieldNames(rendered)).containsExactly("d", "e");
        assertThat(rendered.get("d").size()).isEqualTo(1);
    }

    @Test
    void nullsAreKeptWhenAskedFor() {
        JsonNode rendered = JsonTemplate.compile(Json.parse("""
                {"c": "${request.bos}"}
                """)).render(resolver, true);
        assertThat(rendered.get("c").isNull()).isTrue();
    }

    @Test
    void placeholdersAreReportedWithTheirPath() {
        JsonTemplate template = JsonTemplate.compile(Json.parse("""
                {"a": {"b": ["${request.x}", "k-${user.id}"]}}
                """));
        assertThat(template.placeholders())
                .extracting(located -> located.path() + "=" + located.placeholder())
                .containsExactly("a.b[0]=${request.x}", "a.b[1]=${user.id}");
    }

    @Test
    void unclosedPlaceholdersAreDetected() {
        assertThat(TemplateString.parse("${request.id").hasUnclosedPlaceholder()).isTrue();
        assertThat(TemplateString.parse("${request.id}").hasUnclosedPlaceholder()).isFalse();
    }
}
