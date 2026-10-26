package com.acme.yapi.envadmin.web;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.acme.yapi.core.session.SessionRecord;
import com.acme.yapi.core.session.SessionRecordCodec;
import com.acme.yapi.envadmin.config.EnvAdminProperties;

/**
 * The central admin's sign-in check; it reaches the environment only through this API. The giriş service's record
 * is read with core's codec and matched with the broker's rule: the same token, client IP and user agent as at login.
 * Permissions are given only for a valid session.
 */
@RestController
@RequestMapping("/admin/v1/sessions")
public class SessionController {

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);

    public record VerifyRequest(String uid, String token, String clientIp, String userAgent) {
    }

    public record VerifyResult(boolean valid, List<String> permissions) {

        static final VerifyResult INVALID = new VerifyResult(false, List.of());
    }

    private final StringRedisTemplate redis;
    private final SessionRecordCodec codec;

    public SessionController(StringRedisTemplate redis, EnvAdminProperties properties) {
        this.redis = redis;
        this.codec = properties.session().codec();
    }

    @PostMapping("/verify")
    public VerifyResult verify(@RequestBody VerifyRequest body) {
        if (body.uid() == null || body.uid().isBlank()) {
            return VerifyResult.INVALID;
        }
        String value;
        try {
            value = redis.opsForValue().get(codec.key(body.uid()));
        } catch (RuntimeException e) {
            log.error("Oturum kaydı okunamadı: uid={}", body.uid(), e);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SESSION_UNAVAILABLE", "Oturum kaydı okunamadı.");
        }
        return codec.read(value)
                .filter(record -> record.matches(body.token(), body.clientIp(), body.userAgent()))
                .map(SessionRecord::permissionList)
                .map(permissions -> new VerifyResult(true, permissions))
                .orElse(VerifyResult.INVALID);
    }
}
