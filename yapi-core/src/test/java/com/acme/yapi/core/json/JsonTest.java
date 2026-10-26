package com.acme.yapi.core.json;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void yesIsTrueOneOrTheirText() {
        for (String yes : new String[]{"true", "1", "\"true\"", "\"TRUE\"", "\" 1 \""}) {
            assertThat(Json.isTrue(Json.parse("{\"f\": " + yes + "}").get("f"))).as(yes).isTrue();
        }
        for (String no : new String[]{"false", "0", "2", "null", "\"evet\"", "\"\"", "[]", "{}"}) {
            assertThat(Json.isTrue(Json.parse("{\"f\": " + no + "}").get("f"))).as(no).isFalse();
        }
        assertThat(Json.isTrue(null)).isFalse();
    }
}
