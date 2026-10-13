package com.acme.broker.core.naming;

import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Naming rules shared by the broker, the environment Admin API and the central admin.
 *
 * <p>Lookups ignore case with {@link Locale#ROOT}: under a Turkish default locale
 * {@code "GET_ID".toLowerCase()} becomes {@code "get_ıd"} and would not match {@code "get_id"}.
 */
public final class Names {

    public static final Pattern MAINTASK = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,99}$");
    public static final Pattern SUBTASK = Pattern.compile("^[a-z][a-z0-9]*(_[a-z0-9]+)*$");
    public static final int SUBTASK_MAX_LENGTH = 150;
    public static final Pattern PERMISSION_PREFIX = Pattern.compile("^[A-Z][A-Z0-9_]*[A-Z0-9]$");
    public static final int PERMISSION_PREFIX_MAX_LENGTH = 50;
    public static final Pattern PERMISSION_SUFFIX = Pattern.compile("^[A-Z0-9]+(_[A-Z0-9]+)*$");
    public static final int PERMISSION_MAX_LENGTH = 100;
    public static final Pattern PROFILE_NAME = MAINTASK;

    private Names() {
    }

    /** Case-insensitive lookup key, independent of the JVM's default locale. */
    public static String key(String name) {
        return name == null ? null : name.toLowerCase(Locale.ROOT);
    }

    public static boolean sameName(String a, String b) {
        return a != null && b != null && key(a).equals(key(b));
    }

    public static boolean isValidMaintask(String maintask) {
        return maintask != null && MAINTASK.matcher(maintask).matches();
    }

    public static boolean isValidSubtask(String subtask) {
        return subtask != null && subtask.length() <= SUBTASK_MAX_LENGTH && SUBTASK.matcher(subtask).matches();
    }

    public static boolean isValidPrefix(String prefix) {
        return prefix != null && prefix.length() <= PERMISSION_PREFIX_MAX_LENGTH
                && PERMISSION_PREFIX.matcher(prefix).matches();
    }

    public static boolean isValidSuffix(String suffix) {
        return suffix != null && PERMISSION_SUFFIX.matcher(suffix).matches();
    }

    public static boolean isValidProfileName(String name) {
        return name != null && PROFILE_NAME.matcher(name).matches();
    }

    public static String permission(String prefix, String suffix) {
        return prefix + "_" + suffix;
    }

    /**
     * True when {@code code} is a permission of the project with this prefix: {@code PREFIX_SUFFIX}. Same rule as
     * the {@code check_route_permissions} trigger; the suffix format is enforced only when a permission is created.
     */
    public static boolean belongsTo(String code, String prefix) {
        if (code == null || prefix == null) {
            return false;
        }
        String head = prefix + "_";
        return code.startsWith(head) && code.length() > head.length();
    }

    /**
     * Two prefixes overlap when one is the other followed by {@code _}: with {@code HR} and {@code HR_PORTAL},
     * {@code HR_PORTAL_ADMIN} would belong to both projects.
     */
    public static boolean prefixesOverlap(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return a.equals(b) || a.startsWith(b + "_") || b.startsWith(a + "_");
    }

    /** The registered prefix a permission belongs to, if any; prefixes never overlap so at most one matches. */
    public static Optional<String> ownerPrefix(String code, Collection<String> prefixes) {
        return prefixes.stream().filter(prefix -> belongsTo(code, prefix)).findFirst();
    }
}
