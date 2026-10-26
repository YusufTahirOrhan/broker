package com.acme.yapi.runtime.route;

import com.acme.yapi.core.response.ResponseProfile;

/** A response profile compiled once at load; the request path only fills placeholders. */
public record CompiledProfile(String name, int version, ResponseProfile profile) {
}
