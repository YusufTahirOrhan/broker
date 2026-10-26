package com.acme.yapi.sink.kafka;

/** An event the target refused for good (mapping or parse error): it goes to the DLQ instead of blocking. */
public record Rejection(int index, String reason) {
}
