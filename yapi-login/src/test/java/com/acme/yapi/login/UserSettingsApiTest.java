package com.acme.yapi.login;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.acme.yapi.core.context.UserContextToken;
import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.testing.JsonHttp;
import com.acme.yapi.login.support.TestRedis;

import tools.jackson.databind.json.JsonMapper;

/**
 * Dashboard layout and quick links where the legacy broker kept them; the user is the one the broker signed, never a
 * field of the body.
 */
@Tag("docker")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "login.user-context-secret=k3y",
        "login.ldap.urls=ldap://localhost:1",
        "login.applications.refresh-interval=1h"
})
class UserSettingsApiTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestRedis.register(registry, 2);
    }

    @LocalServerPort
    int port;

    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    void reset() {
        redis.delete(redis.keys("user:*"));
    }

    private JsonHttp.Response call(String method, String name, String userId, String body) {
        Map<String, String> headers = new HashMap<>();
        if (!"unsigned".equals(userId)) {
            headers.put("X-User-Context", UserContextToken.sign("k3y".getBytes(), userId, UUID.randomUUID().toString(),
                    Instant.now()));
        }
        headers.put("X-uid", "spoofed");
        return new JsonHttp("http://localhost:" + port).send(method, "/users/me/settings/" + name, body, headers);
    }

    @Test
    void aSettingIsStoredAsTheLegacyRedisTemplateWroteItAndReadBack() throws Exception {
        String layout = """
                {"uid": "99999", "widgets": [ {"id": "talepler", "x": 0, "y": 1.5, "visible": true},
                                              {"id": "duyurular", "baslik": "Duyurular – Şubat"} ], "theme": null}
                """;

        JsonHttp.Response saved = call("PUT", "layout", "12345", layout);

        assertThat(saved.status()).as(saved.text()).isEqualTo(200);
        String legacy = JsonMapper.builder().build().writeValueAsString(
                JsonMapper.builder().build().readValue(layout, Object.class));
        assertThat(redis.opsForValue().get("user:layout:12345")).isEqualTo(legacy);
        assertThat(saved.text()).isEqualTo(legacy);
        assertThat(redis.getExpire("user:layout:12345")).isEqualTo(-1L);
        assertThat(redis.hasKey("user:layout:99999")).isFalse();
        assertThat(redis.hasKey("user:layout:spoofed")).isFalse();

        JsonHttp.Response read = call("GET", "layout", "12345", null);
        assertThat(read.status()).isEqualTo(200);
        assertThat(read.text()).isEqualTo(legacy);
    }

    @Test
    void whatTheLegacyBrokerWroteIsReadAsItIs() {
        redis.opsForValue().set("user:quick_link:12345", "[{\"ad\":\"İzin\",\"url\":\"/izin\"}]");

        assertThat(call("GET", "quick_link", "12345", null).text()).isEqualTo("[{\"ad\":\"İzin\",\"url\":\"/izin\"}]");
        JsonHttp.Response none = call("GET", "quick_link", "67890", null);
        assertThat(none.status()).isEqualTo(200);
        assertThat(none.text()).isEqualTo("null");
    }

    @Test
    void onlyKnownSettingsOfASignedUserWithValidJson() {
        assertThat(call("GET", "theme", "12345", null).status()).isEqualTo(404);
        assertThat(call("PUT", "theme", "12345", "{}").status()).isEqualTo(404);

        JsonHttp.Response anonymous = call("PUT", "layout", null, "{}");
        assertThat(anonymous.status()).isEqualTo(403);
        assertThat(anonymous.json()).isEqualTo(Json.parse("""
                {"response": false, "status": 403, "message": "Çağrı bir kullanıcıya ait değil; route oturumlu olmalı."}
                """));
        assertThat(call("GET", "layout", "unsigned", null).status()).isEqualTo(403);
        assertThat(call("PUT", "layout", "12345", "{not json").status()).isEqualTo(400);
        assertThat(call("PUT", "layout", "12345", null).status()).isEqualTo(400);
        assertThat(redis.keys("user:*")).isEmpty();
    }
}
