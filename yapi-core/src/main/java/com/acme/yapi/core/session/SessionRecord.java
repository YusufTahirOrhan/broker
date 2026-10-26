package com.acme.yapi.core.session;

import java.util.List;
import java.util.Objects;

/**
 * A user's session as the login writes it to Redis ({@code user:session:{uid}}), the same record the legacy broker
 * reads and writes. One session per user: a new login overwrites the old one.
 *
 * @param token       the {@code X-Token} the login returned
 * @param clientIp    {@code X-Clientip} of the login request
 * @param userAgent   {@code X-User-Agent} of the login request
 * @param permissions the user's permissions, in the order the permission service gave them
 */
public record SessionRecord(String uid, String token, String clientIp, String userAgent, List<String> permissions) {

    public SessionRecord {
        permissions = permissions == null ? null : List.copyOf(permissions);
    }

    /**
     * Whether a request carries this session: {@code X-Token}, {@code X-Clientip} and {@code X-User-Agent} equal the
     * stored values exactly (case-sensitive); a missing header or value never matches.
     */
    public boolean matches(String token, String clientIp, String userAgent) {
        return same(this.token, token) && same(this.clientIp, clientIp) && same(this.userAgent, userAgent);
    }

    /** Whether a logout from this client may close the session: {@code X-Clientip} and {@code X-User-Agent} equal. */
    public boolean sameClient(String clientIp, String userAgent) {
        return same(this.clientIp, clientIp) && same(this.userAgent, userAgent);
    }

    public List<String> permissionList() {
        return permissions == null ? List.of() : permissions;
    }

    private static boolean same(String stored, String given) {
        return stored != null && given != null && Objects.equals(stored, given);
    }
}
