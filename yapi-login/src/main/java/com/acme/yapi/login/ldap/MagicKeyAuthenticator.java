package com.acme.yapi.login.ldap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DEV's login, as the legacy DEV class did it: any non-blank uid with the configured key as the password
 * ({@code YAPI_LOGIN_MAGIC_KEY}, compared in constant time), so anyone can sign in as anyone. Permissions still come
 * from the Koordinatör. Every attempt is logged with its uid, never with the password.
 */
public class MagicKeyAuthenticator implements LdapAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(MagicKeyAuthenticator.class);

    private final byte[] key;

    public MagicKeyAuthenticator(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("login.ldap.magic-key boş; magic-key modu anahtar ister (YAPI_LOGIN_MAGIC_KEY)");
        }
        this.key = key.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean authenticate(String uid, String password) {
        if (uid == null || uid.isBlank()) {
            return false;
        }
        boolean ok = password != null && MessageDigest.isEqual(key, password.getBytes(StandardCharsets.UTF_8));
        log.info("Magic key ile giriş denemesi: uid={}, başarılı={}", uid, ok);
        return ok;
    }
}
