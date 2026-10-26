package com.acme.yapi.sink.kafka;

/** The target cannot take writes right now; the batch is retried and its offsets are not committed. */
public class SinkUnavailableException extends RuntimeException {

    public SinkUnavailableException(String message) {
        super(message);
    }

    public SinkUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
