package com.acme.yapi.core.route;

/** How the backend request body is produced. */
public enum BodyMode {
    /** No body. The only mode allowed for GET and DELETE. */
    NONE,
    /** The frontend body without the envelope fields, unchanged. */
    PASS_THROUGH,
    /** A JSON template filled with placeholders. */
    TEMPLATE,
    /** multipart/form-data built from parts. */
    MULTIPART
}
