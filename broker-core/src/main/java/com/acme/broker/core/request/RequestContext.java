package com.acme.broker.core.request;

import java.util.function.Function;

import com.acme.broker.core.json.Json;
import com.acme.broker.core.route.RouteValidator;
import com.acme.broker.core.template.Placeholder;
import com.acme.broker.core.template.Resolver;

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
 */
public record RequestContext(ObjectNode originalBody, ObjectNode effectiveBody, Function<String, String> headerLookup,
                             RouteValidator.Context readable, String userId, String requestId) {

    public Resolver resolver() {
        return placeholder -> switch (placeholder.root()) {
            case Placeholder.REQUEST -> placeholder.path().isEmpty()
                    ? Json.missing()
                    : RequestBodyValidator.find(effectiveBody, placeholder.path());
            case Placeholder.HEADER -> header(placeholder.path());
            case Placeholder.USER -> "id".equals(placeholder.path()) && userId != null
                    ? Json.string(userId) : Json.missing();
            case Placeholder.REQUEST_ID -> Json.string(requestId);
            default -> Json.missing();
        };
    }

    private JsonNode header(String name) {
        if (name.isEmpty() || !readable.canRead(name)) {
            return Json.missing();
        }
        String value = headerLookup.apply(name);
        return value == null ? Json.missing() : Json.string(value);
    }
}
