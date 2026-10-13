package com.acme.broker.core.route;

/** What the backend returns. BINARY is streamed, its body is never logged and no profile is applied. */
public enum ResponseType {
    JSON, TEXT, BINARY
}
