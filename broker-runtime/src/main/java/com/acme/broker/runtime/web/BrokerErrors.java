package com.acme.broker.runtime.web;

import java.util.List;

import com.acme.broker.core.json.Json;
import com.acme.broker.core.response.Outcome;
import com.acme.broker.core.validation.FieldError;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** The broker's own error format, shared by every error it gives without the backend's answer. */
final class BrokerErrors {

    private BrokerErrors() {
    }

    static ObjectNode body(int status, Outcome outcome, String message, String requestId, List<FieldError> errors) {
        ObjectNode body = Json.object();
        body.put("result", false);
        body.put("status", status);
        body.put("outcome", outcome.name());
        body.put("message", message);
        body.put("requestId", requestId);
        ArrayNode list = body.putArray("errors");
        for (FieldError error : errors) {
            ObjectNode item = list.addObject();
            item.put("field", error.field());
            item.put("message", error.message());
        }
        return body;
    }

    /** The legacy broker's answer to an application that is not allowed, with HTTP 200; frontends depend on it. */
    static ObjectNode legacyRefused() {
        ObjectNode body = Json.object();
        body.put("response", false);
        return body;
    }

    /** The legacy broker's answer to an invalid session ({@code token_check}), with HTTP 200. */
    static ObjectNode legacySessionInvalid() {
        ObjectNode body = legacyRefused();
        body.put("status", 301);
        body.put("reason", "check X-Token");
        return body;
    }

    /** What the frontend gets when the log could not be written, exactly as today. */
    static ObjectNode logUnavailableBody() {
        ObjectNode body = Json.object();
        body.put("result", false);
        return body;
    }
}
