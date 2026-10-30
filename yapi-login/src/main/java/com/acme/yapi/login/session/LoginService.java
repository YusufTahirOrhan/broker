package com.acme.yapi.login.session;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.acme.yapi.core.session.SessionRecord;
import com.acme.yapi.login.config.LoginProperties;
import com.acme.yapi.login.koordinator.KoordinatorClient;
import com.acme.yapi.login.ldap.LdapAuthenticator;

/** Login and logout with the legacy broker's decisions; the bodies are in {@code LegacyBodies}. */
@Service
public class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    private final LdapAuthenticator ldap;
    private final KoordinatorClient koordinator;
    private final SessionStore sessions;
    private final boolean requireClientMatch;

    public LoginService(LdapAuthenticator ldap, KoordinatorClient koordinator, SessionStore sessions,
                        LoginProperties properties) {
        this.ldap = ldap;
        this.koordinator = koordinator;
        this.sessions = sessions;
        this.requireClientMatch = properties.logout().requireClientMatch();
    }

    public sealed interface LoginResult {

        /** The record is in Redis; {@code token} is the new {@code X-Token}. */
        record LoggedIn(String token) implements LoginResult {
        }

        /** LDAP and permissions were fine but the record could not be written. */
        record NotStored() implements LoginResult {
        }

        record Refused(boolean ldap) implements LoginResult {
        }
    }

    /**
     * LDAP bind, then the user's permissions from the Koordinatör (only after a successful bind), then the record.
     * The legacy code asked the Koordinatör either way; the answer is the same. A user who logs in again from the
     * client of their open session gets that session's token back and the record takes the Koordinatör's new
     * permissions, keeping its remaining time; when that write fails the login still succeeds with the old record.
     * From another client the new session replaces the old one.
     */
    public LoginResult login(String uid, String password, String clientIp, String userAgent) {
        boolean ldapOk = ldap.authenticate(uid, password);
        Optional<List<String>> permissions = ldapOk ? koordinator.permissions(uid) : Optional.empty();
        if (!ldapOk || permissions.isEmpty()) {
            log.info("Giriş reddedildi: uid={}, ldap={}, yetki={}", uid, ldapOk, permissions.isPresent());
            return new LoginResult.Refused(ldapOk);
        }
        Optional<SessionRecord> open = openSession(uid, clientIp, userAgent);
        if (open.isPresent()) {
            SessionRecord record = open.get();
            if (!sessions.rewrite(new SessionRecord(uid, record.token(), record.clientIp(), record.userAgent(),
                    permissions.get()))) {
                log.warn("Açık oturumun yetkileri güncellenemedi, kayıt olduğu gibi kaldı: uid={}", uid);
            }
            log.info("Giriş yapıldı, açık oturum sürüyor: uid={}", uid);
            return new LoginResult.LoggedIn(record.token());
        }
        String token = UUID.randomUUID().toString();
        if (!sessions.write(new SessionRecord(uid, token, clientIp, userAgent, permissions.get()))) {
            return new LoginResult.NotStored();
        }
        log.info("Giriş yapıldı: uid={}", uid);
        return new LoginResult.LoggedIn(token);
    }

    /** The user's session opened from this client; empty when there is none or Redis cannot tell. */
    private Optional<SessionRecord> openSession(String uid, String clientIp, String userAgent) {
        try {
            return sessions.find(uid).filter(record -> record.sameClient(clientIp, userAgent))
                    .filter(record -> record.token() != null && !record.token().isBlank());
        } catch (RuntimeException e) {
            log.warn("Açık oturum okunamadı, yeni oturum açılıyor: uid={}", uid, e);
            return Optional.empty();
        }
    }

    /**
     * Closes the session of {@code uid} when the request comes from the client that opened it (the legacy broker
     * looked at the uid alone, so anyone could close anyone's session). False for anything else, errors included.
     */
    public boolean logout(String uid, String clientIp, String userAgent) {
        if (uid == null || uid.isBlank()) {
            return false;
        }
        try {
            Optional<SessionRecord> record = sessions.find(uid);
            if (record.isEmpty() || requireClientMatch && !record.get().sameClient(clientIp, userAgent)) {
                log.info("Çıkış reddedildi: uid={}, oturum={}", uid, record.isPresent());
                return false;
            }
            return sessions.delete(uid);
        } catch (RuntimeException e) {
            log.error("Çıkış yapılamadı: uid={}", uid, e);
            return false;
        }
    }

    /** The Koordinatör's forced logout; true when there was a session. */
    public boolean expire(String uid) {
        boolean deleted = sessions.delete(uid);
        log.info("Koordinatör oturumu kapattı: uid={}, oturum={}", uid, deleted);
        return deleted;
    }
}
