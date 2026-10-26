package com.acme.yapi.login;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.session.SessionRecord;
import com.acme.yapi.core.session.SessionRecordCodec;
import com.acme.yapi.core.testing.JsonHttp;
import com.acme.yapi.login.session.ApplicationSync;
import com.acme.yapi.login.support.TestRedis;

/**
 * The {@code local} profile runs on a developer's machine against DEV's Redis: it writes only its own keys and
 * leaves DEV's sessions and application list as they are.
 */
@Tag("docker")
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LocalProfileTest {

    static final String DEV_APPLICATIONS = "[\"web\",\"mobil\",\"kiosk\"]";
    static final String DEV_SESSION = SessionRecordCodec.DEFAULT.write(
            new SessionRecord("dev.ali", "dev-token", "10.9.9.9", "DEV browser", List.of("ORDER_READ")));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestRedis.register(registry, 1);
    }

    @LocalServerPort
    int port;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    ApplicationSync applications;

    @Test
    void theLocalServiceWritesOnlyLocalKeys() {
        // what DEV's login service wrote
        redis.opsForValue().set("yapi:applications", DEV_APPLICATIONS);
        redis.opsForValue().set("user:session:dev.ali", DEV_SESSION);
        JsonHttp http = new JsonHttp("http://localhost:" + port);
        Map<String, String> client = Map.of("X-Clientip", "127.0.0.1", "X-User-Agent", "curl");

        applications.sync();
        JsonHttp.Response login = http.send("POST", "/login", "{\"uid\": \"dev.ali\", \"password\": \"dev.ali\"}",
                client);

        assertThat(login.json().get("login").booleanValue()).as(login.text()).isTrue();
        String token = login.json().get("X-Token").stringValue();
        assertThat(redis.keys("*")).containsExactlyInAnyOrder("yapi:applications", "user:session:dev.ali",
                "yapi:applications:local", "local:user:session:dev.ali");
        assertThat(SessionRecordCodec.DEFAULT.read(redis.opsForValue().get("local:user:session:dev.ali")))
                .get().extracting(SessionRecord::token).isEqualTo(token);
        assertThat(Json.parse(redis.opsForValue().get("yapi:applications:local")))
                .isEqualTo(Json.parse("[\"web\", \"mobil\"]"));

        assertThat(http.send("POST", "/logout", "{\"uid\": \"dev.ali\"}", client).json().get("response")
                .booleanValue()).isTrue();
        assertThat(redis.hasKey("local:user:session:dev.ali")).isFalse();
        assertThat(redis.opsForValue().get("yapi:applications")).isEqualTo(DEV_APPLICATIONS);
        assertThat(redis.opsForValue().get("user:session:dev.ali")).isEqualTo(DEV_SESSION);
    }
}
