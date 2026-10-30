package com.acme.yapi.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.session.SessionRecord;
import com.acme.yapi.core.session.SessionRecordCodec;
import com.acme.yapi.login.config.LoginProperties;
import com.acme.yapi.login.koordinator.KoordinatorClient;
import com.acme.yapi.login.koordinator.StaticKoordinatorClient;
import com.acme.yapi.login.ldap.StaticLdapAuthenticator;
import com.acme.yapi.login.session.LoginService;
import com.acme.yapi.login.session.SessionStore;
import com.acme.yapi.login.web.LoginController;

/** What needs a broken Redis. */
@ExtendWith(OutputCaptureExtension.class)
class LoginUnitTest {

    private static final LoginProperties PROPERTIES = new LoginProperties(null, null, null, null, null, null, null, null, null);

    @SuppressWarnings("unchecked")
    private static LoginController controller(StringRedisTemplate redis) {
        KoordinatorClient koordinator = new StaticKoordinatorClient(List.of("LOGPROJECT_TEMEL"), List.of());
        LoginService service = new LoginService(new StaticLdapAuthenticator(Map.of("ali", "p")), koordinator,
                new SessionStore(redis, PROPERTIES), PROPERTIES);
        return new LoginController(service, PROPERTIES);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aRecordThatCannotBeWrittenGivesTheLegacyFailure() {
        StringRedisTemplate down = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(down.opsForValue()).thenReturn(values);
        doThrow(new RedisConnectionFailureException("down")).when(values).set(anyString(), anyString(),
                any(Duration.class));
        StringRedisTemplate lost = mock(StringRedisTemplate.class);
        when(lost.opsForValue()).thenReturn(mock(ValueOperations.class));
        when(lost.hasKey(anyString())).thenReturn(false);
        byte[] body = "{\"uid\": \"ali\", \"password\": \"p\"}".getBytes();

        for (StringRedisTemplate redis : List.of(down, lost)) {
            assertThat(Json.write(controller(redis).login(body, "10.0.0.1", "agent")))
                    .isEqualTo("{\"status\":401,\"Ldap\":false,\"login\":false,\"X-Token\":\"404\"}");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void aLoginAgainWhosePermissionsCannotBeWrittenKeepsTheOpenSession(CapturedOutput output) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.getStringSerializer()).thenReturn(new StringRedisTemplate().getStringSerializer());
        when(values.get("user:session:ali")).thenReturn(SessionRecordCodec.DEFAULT.write(
                new SessionRecord("ali", "open-token", "10.0.0.1", "agent", List.of("OLD"))));
        doThrow(new RedisConnectionFailureException("down")).when(redis).execute(any(RedisCallback.class));
        byte[] body = "{\"uid\": \"ali\", \"password\": \"p\"}".getBytes();

        assertThat(Json.write(controller(redis).login(body, "10.0.0.1", "agent")))
                .contains("\"login\":true", "\"X-Token\":\"open-token\"");
        assertThat(output.getAll()).contains("Açık oturumun yetkileri güncellenemedi");
    }
}
