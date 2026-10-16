package com.acme.broker.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.acme.broker.core.json.Json;
import com.acme.broker.login.config.KoordinatorProperties;
import com.acme.broker.login.config.LoginProperties;
import com.acme.broker.login.koordinator.KoordinatorClient;
import com.acme.broker.login.ldap.StaticLdapAuthenticator;
import com.acme.broker.login.session.LoginService;
import com.acme.broker.login.session.SessionStore;
import com.acme.broker.login.web.LoginController;

/** What needs a broken Redis. */
class LoginUnitTest {

    private static final LoginProperties PROPERTIES = new LoginProperties(null, null, null, null, null, null, null);

    @SuppressWarnings("unchecked")
    private static LoginController controller(StringRedisTemplate redis) {
        KoordinatorClient koordinator = new KoordinatorClient(new KoordinatorProperties(null, null, null, null, null,
                null, null, null)) {
            @Override
            public Optional<List<String>> permissions(String uid) {
                return Optional.of(List.of("LOGPROJECT_TEMEL"));
            }
        };
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
}
