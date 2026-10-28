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
    /** {@code ${alarm.values}}: the request's values, only in an alarm service route. */
    public static final String ALARM = "alarm";

    public static Placeholder parse(String expression) {
        String trimmed = expression.trim();
        // ${now:dd.MM.yyyy}: the pattern may hold dots; the path keeps the colon
        if (trimmed.startsWith(ContextPlaceholders.NOW + ":")) {
            return new Placeholder(ContextPlaceholders.NOW, trimmed.substring(ContextPlaceholders.NOW.length()));
        }
        int dot = trimmed.indexOf('.');
        if (dot < 0) {
            return new Placeholder(trimmed, "");
        }
        return new Placeholder(trimmed.substring(0, dot), trimmed.substring(dot + 1));
    }

    public String expression() {
        return path.isEmpty() ? root : path.startsWith(":") ? root + path : root + "." + path;
    }

    @Override
    public String toString() {
        return "${" + expression() + "}";
    }
}
