package com.acme.broker.runtime.web;

import java.time.Instant;
import java.util.UUID;

import com.acme.broker.core.ids.Ids;
import com.acme.broker.runtime.forward.TraceContext;
import com.acme.broker.runtime.route.Route;
import com.acme.broker.runtime.session.Session;

import tools.jackson.databind.node.ObjectNode;

/** What one request accumulates on its way through the broker; the controller keeps no other state. */
final class Exchange {

    final UUID requestUuid = Ids.uuidV7();
    final String requestId = requestUuid.toString();
    final String queryId = UUID.randomUUID().toString();
    final Instant receivedAt = Ids.timeOf(requestUuid);
    final long startedNanos = System.nanoTime();
    final TraceContext trace;
    final String token;
    final String clientIp;
    final String userAgent;

    byte[] rawBody = new byte[0];
    /** The frontend body; null when it was not a JSON object. */
    ObjectNode body;
    String maintask;
    String subtask;
    String application;
    String uid;
    String sessionOperation;
    /** Registered spelling of the project when the route is unknown but the project is not. */
    String knownProject;
    Route route;
    Session session;
    String targetPath;
    boolean requestLogged;

    Exchange(TraceContext trace, String token, String clientIp, String userAgent) {
        this.trace = trace;
        this.token = token;
        this.clientIp = clientIp;
        this.userAgent = userAgent;
    }

    String metricMaintask() {
        if (route != null) {
            return route.maintask();
        }
        return knownProject;
    }

    String metricSubtask() {
        return route == null ? null : route.subtask();
    }
}
