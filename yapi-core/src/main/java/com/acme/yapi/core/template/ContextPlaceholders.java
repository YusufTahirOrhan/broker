package com.acme.yapi.core.template;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.request.RequestScope;

import tools.jackson.databind.JsonNode;

/**
 * Placeholders that read the request as a whole rather than its body: time, identifiers, the route, the client and
 * the user's permissions. Definitions, response profiles and plugins all use them.
 *
 * <ul>
 *   <li>{@code ${now}}: local date-time in the scope's zone, {@code yyyy-MM-dd'T'HH:mm:ss.SSS};
 *       {@code ${now:<pattern>}} with a {@link DateTimeFormatter} pattern; {@code ${today}} {@code yyyy-MM-dd};
 *       {@code ${epochMillis}} a number</li>
 *   <li>{@code ${uuid}} (a new one each time), {@code ${traceId}}, {@code ${environment}}</li>
 *   <li>{@code ${route.maintask}}, {@code ${route.subtask}}, {@code ${route.version}}</li>
 *   <li>{@code ${client.ip}}, {@code ${client.userAgent}} ({@code X-Clientip}, {@code X-User-Agent})</li>
 *   <li>{@code ${user.permissions}}: the verified user's permissions as an array (read by {@code RequestContext})</li>
 * </ul>
 */
public final class ContextPlaceholders {

    public static final String NOW = "now";
    public static final String TODAY = "today";
    public static final String EPOCH_MILLIS = "epochMillis";
    public static final String UUID_ROOT = "uuid";
    public static final String TRACE_ID = "traceId";
    public static final String ENVIRONMENT = "environment";
    public static final String ROUTE = "route";
    public static final String CLIENT = "client";

    private static final Set<String> ROOTS = Set.of(NOW, TODAY, EPOCH_MILLIS, UUID_ROOT, TRACE_ID, ENVIRONMENT, ROUTE,
            CLIENT);
    private static final DateTimeFormatter NOW_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

    /** As shown in {@code definition-rules}. */
    public static final List<String> SHOWN = List.of("${now}", "${now:<desen>}", "${today}", "${epochMillis}",
            "${uuid}", "${traceId}", "${environment}", "${route.maintask}", "${route.subtask}", "${route.version}",
            "${client.ip}", "${client.userAgent}", "${user.permissions}");

    private ContextPlaceholders() {
    }

    public static boolean handles(Placeholder placeholder) {
        return ROOTS.contains(placeholder.root());
    }

    /** Why the placeholder is malformed, or null; only for placeholders this class {@link #handles}. */
    public static String problem(Placeholder placeholder) {
        String path = placeholder.path();
        return switch (placeholder.root()) {
            case NOW -> {
                if (path.isEmpty()) {
                    yield null;
                }
                if (!path.startsWith(":") || path.length() == 1) {
                    yield "Tarih biçimi ${now:<desen>} şeklinde yazılır: " + placeholder;
                }
                try {
                    DateTimeFormatter.ofPattern(path.substring(1));
                    yield null;
                } catch (IllegalArgumentException e) {
                    yield "Geçersiz tarih deseni: " + path.substring(1);
                }
            }
            case ROUTE -> Set.of("maintask", "subtask", "version").contains(path) ? null
                    : "Route için ${route.maintask}, ${route.subtask} ya da ${route.version} kullanılabilir";
            case CLIENT -> Set.of("ip", "userAgent").contains(path) ? null
                    : "İstemci için ${client.ip} ya da ${client.userAgent} kullanılabilir";
            default -> path.isEmpty() ? null : "Bilinmeyen yer tutucu: " + placeholder;
        };
    }

    /** The value, or missing when the scope does not have it (no scope at all, a preview without a version). */
    public static JsonNode resolve(Placeholder placeholder, RequestScope scope) {
        if (scope == null) {
            return Json.missing();
        }
        String path = placeholder.path();
        return switch (placeholder.root()) {
            case NOW -> {
                LocalDateTime now = LocalDateTime.ofInstant(scope.clock().instant(), scope.zone());
                yield Json.string(path.isEmpty() ? NOW_FORMAT.format(now)
                        : DateTimeFormatter.ofPattern(path.substring(1)).format(now));
            }
            case TODAY -> Json.string(LocalDate.ofInstant(scope.clock().instant(), scope.zone()).toString());
            case EPOCH_MILLIS -> Json.number(scope.clock().millis());
            case UUID_ROOT -> Json.string(UUID.randomUUID().toString());
            case TRACE_ID -> text(scope.traceId());
            case ENVIRONMENT -> text(scope.environment());
            case ROUTE -> switch (path) {
                case "maintask" -> text(scope.maintask());
                case "subtask" -> text(scope.subtask());
                case "version" -> scope.routeVersion() == null ? Json.missing() : Json.number(scope.routeVersion());
                default -> Json.missing();
            };
            case CLIENT -> switch (path) {
                case "ip" -> text(scope.clientIp());
                case "userAgent" -> text(scope.userAgent());
                default -> Json.missing();
            };
            default -> Json.missing();
        };
    }

    /** {@code ${requestReceivedAt}} and the like: an instant as {@code ${now}} writes it. */
    public static JsonNode localTime(java.time.Instant instant, RequestScope scope) {
        if (instant == null || scope == null) {
            return Json.missing();
        }
        return Json.string(NOW_FORMAT.format(LocalDateTime.ofInstant(instant, scope.zone())));
    }

    private static JsonNode text(String value) {
        return value == null ? Json.missing() : Json.string(value);
    }
}
