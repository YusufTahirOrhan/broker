package com.acme.broker.runtime.forward;

import java.util.regex.Pattern;

import com.acme.broker.core.ids.Ids;

/**
 * W3C trace context. The trace continues the frontend's {@code traceparent} when it sent a valid one; the broker's
 * call to the backend gets a new span ID.
 */
public record TraceContext(String traceId, String spanId, String flags) {

    private static final Pattern TRACEPARENT =
            Pattern.compile("^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");
    private static final String INVALID_TRACE = "0".repeat(32);

    public static TraceContext from(String traceparent) {
        if (traceparent != null) {
            var matcher = TRACEPARENT.matcher(traceparent.trim());
            if (matcher.matches() && !matcher.group(1).equals(INVALID_TRACE)) {
                return new TraceContext(matcher.group(1), Ids.spanId(), matcher.group(3));
            }
        }
        return new TraceContext(Ids.traceId(), Ids.spanId(), "01");
    }

    public String traceparent() {
        return "00-" + traceId + "-" + spanId + "-" + flags;
    }
}
