package com.acme.yapi.core.template;

/**
 * A {@code ${root.path}} reference. {@code ${request.adres.il}} has root {@code request} and path
 * {@code adres.il}; {@code ${requestId}} has root {@code requestId} and an empty path.
 */
public record Placeholder(String root, String path) {

    public static final String REQUEST = "request";
    public static final String HEADER = "header";
    public static final String USER = "user";
    public static final String REQUEST_ID = "requestId";
    public static final String BACKEND = "backend";
    public static final String OUTCOME = "outcome";

    public static Placeholder parse(String expression) {
        String trimmed = expression.trim();
        int dot = trimmed.indexOf('.');
        if (dot < 0) {
            return new Placeholder(trimmed, "");
        }
        return new Placeholder(trimmed.substring(0, dot), trimmed.substring(dot + 1));
    }

    public String expression() {
        return path.isEmpty() ? root : root + "." + path;
    }

    @Override
    public String toString() {
        return "${" + expression() + "}";
    }
}
