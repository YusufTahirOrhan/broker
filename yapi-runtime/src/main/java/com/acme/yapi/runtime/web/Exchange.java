package com.acme.yapi.runtime.web;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.acme.yapi.core.ids.Ids;
import com.acme.yapi.core.logging.LogEvent;
import com.acme.yapi.core.plugin.Plugin;
import com.acme.yapi.core.request.RequestContext;
import com.acme.yapi.runtime.forward.TraceContext;
import com.acme.yapi.runtime.route.Route;
import com.acme.yapi.runtime.session.Session;

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
    /** The alarm check's record for the RESPONSE event; null when no check ran. */
    ObjectNode alarm;
    /** Why the broker refused the request; null unless the outcome is {@code DENIED}. */
    LogEvent.DeniedReason deniedReason;
    /** The route the refused request asked for, when there was one ({@link #route} may still be unset). */
    Route deniedRoute;
    /** Whether the refused user holds a permission of the route's project; null when that cannot be told. */
    Boolean hasProjectPermission;
    /** The route's plugins, in the order they apply; empty for session operations. */
    List<Plugin> plugins = List.of();
    /** The plugins that changed the request or the response, for the RESPONSE event. */
    final Set<Plugin.Applied> appliedPlugins = new LinkedHashSet<>();
    /** The request's placeholders, for the response operations of the plugins. */
    RequestContext context;
    /** The service call's duration until its status and headers arrived. */
    Long backendDurationMs;

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
