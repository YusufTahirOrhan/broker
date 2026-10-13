package com.acme.broker.core.template;

import tools.jackson.databind.JsonNode;

/** Supplies placeholder values. Returns a missing node when the value does not exist. */
@FunctionalInterface
public interface Resolver {

    JsonNode resolve(Placeholder placeholder);
}
