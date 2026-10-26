package com.acme.yapi.core.response;

/** Same value in metrics, log events and ClickHouse. */
public enum Outcome {
    SUCCESS,
    BUSINESS_ERROR,
    TECHNICAL_ERROR,
    TIMEOUT,
    DENIED,
    UNKNOWN_TASK,
    INVALID_REQUEST,
    /** The alarm service found an alarm in the request's values; the service was not called. */
    ALARM,
    LOG_UNAVAILABLE;

    /** Outcome of a backend response, computed from the real status before any profile wraps it. */
    public static Outcome fromBackendStatus(int status) {
        if (status >= 200 && status < 300) {
            return SUCCESS;
        }
        if (status >= 500) {
            return TECHNICAL_ERROR;
        }
        return BUSINESS_ERROR;
    }

    /** Outcomes produced after the backend was called. */
    public boolean fromBackend() {
        return this == SUCCESS || this == BUSINESS_ERROR || this == TECHNICAL_ERROR || this == TIMEOUT;
    }
}
