package com.acme.yapi.core.request;

import java.util.Collection;
import java.util.List;
import java.util.function.Function;

import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.route.RouteValidator;
import com.acme.yapi.core.template.ContextPlaceholders;
import com.acme.yapi.core.template.Placeholder;
import com.acme.yapi.core.template.Resolver;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Everything a definition's placeholders can read for one request.
 *
 * @param originalBody  the frontend body as received; PASS_THROUGH sends it without the envelope fields
 * @param effectiveBody the body after defaults were applied; {@code ${request.*}} reads it
 * @param headerLookup  case-insensitive lookup of the frontend request's headers
 * @param readable      which headers {@code ${header.*}} may read
 * @param userId        user of the verified session, null for anonymous calls without a session
 * @param permission    {@code ${user.permission}}: the first of the route's permissions the user has
 *                      ({@link #firstPermission}); null without a session or without such a permission
 * @param alarmValues   {@code ${alarm.values}} in an alarm service route: the checked request's values; null elsewhere
 * @param scope         what the request-wide placeholders read ({@link ContextPlaceholders}); null leaves them
 *                      without a value
 */
public record RequestContext(ObjectNode originalBody, ObjectNode effectiveBody, Function<String, String> headerLookup,
                             RouteValidator.Context readable, String userId, String permission, String requestId,
                             List<String> alarmValues, RequestScope scope) {

    public RequestContext(ObjectNode originalBody, ObjectNode effectiveBody, Function<String, String> headerLookup,
                          RouteValidator.Context readable, String userId, String permission, String requestId) {
        this(originalBody, effectiveBody, headerLookup, readable, userId, permission, requestId, null, null);
    }

    /** The context of an alarm service call made for this request. */
    public RequestContext withAlarmValues(List<String> values) {
        return new RequestContext(originalBody, effectiveBody, headerLookup, readable, userId, permission, requestId,
                List.copyOf(values), scope);
    }

    public RequestContext withScope(RequestScope requestScope) {
        return new RequestContext(originalBody, effectiveBody, headerLookup, readable, userId, permission, requestId,
                alarmValues, requestScope);
    }

    /**
     * The first of {@code routePermissions} (the route's own permissions, then the ones added from other projects, in
     * the definition's order) that the user has; null when none. The legacy broker picked {@code perm} for
     * {@code sorgu} this way: {@code SORGU_TEMEL} first, then {@code SORGU_ILERI}.
     */
    public static String firstPermission(List<String> routePermissions, Collection<String> userPermissions) {
        if (routePermissions == null || userPermissions == null) {
            return null;
        }
        for (String permission : routePermissions) {
            if (userPermissions.contains(permission)) {
                return permission;
            }
        }
        return null;
    }

    public Resolver resolver() {
        return placeholder -> switch (placeholder.root()) {
            case Placeholder.REQUEST -> placeholder.path().isEmpty()
                    ? Json.missing()
                    : RequestBodyValidator.find(effectiveBody, placeholder.path());
            case Placeholder.HEADER -> header(placeholder.path());
            case Placeholder.USER -> user(placeholder.path());
            case Placeholder.REQUEST_ID -> Json.string(requestId);
            case Placeholder.ALARM -> "values".equals(placeholder.path()) && alarmValues != null
                    ? Json.stringArray(alarmValues) : Json.missing();
            default -> ContextPlaceholders.handles(placeholder)
                    ? ContextPlaceholders.resolve(placeholder, scope) : Json.missing();
        };
    }

    private JsonNode user(String field) {
        if ("permissions".equals(field)) {
            return scope == null || userId == null ? Json.missing() : Json.stringArray(scope.userPermissions());
        }
        String value = switch (field) {
            case "id" -> userId;
            case "permission" -> permission;
            default -> null;
        };
        return value == null ? Json.missing() : Json.string(value);
    }

    private JsonNode header(String name) {
        if (name.isEmpty() || !readable.canRead(name)) {
            return Json.missing();
        }
        String value = headerLookup.apply(name);
        return value == null ? Json.missing() : Json.string(value);
    }
}
