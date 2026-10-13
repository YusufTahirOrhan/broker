package com.acme.broker.runtime.route;

import com.acme.broker.core.response.ResponseProfile;

/** A response profile compiled once at load; the request path only fills placeholders. */
public record CompiledProfile(String name, int version, ResponseProfile profile) {
}
