package com.acme.broker.core.response;

import tools.jackson.databind.JsonNode;

/** What the frontend receives: HTTP status and JSON body. */
public record ClientResponse(int status, JsonNode body) {
}
