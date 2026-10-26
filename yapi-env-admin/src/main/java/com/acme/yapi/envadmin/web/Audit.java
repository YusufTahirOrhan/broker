package com.acme.yapi.envadmin.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The Admin API's own record of who changed what: every call carries the acting user and, for promotions, the
 * central {@code promotion_id}. Written as key-value pairs so structured (ECS) logging keeps them as fields.
 */
public final class Audit {

    public static final String ACTOR_HEADER = "X-Actor";
    static final String CLIENT_ATTRIBUTE = Audit.class.getName() + ".client";

    private static final Logger log = LoggerFactory.getLogger("yapi.audit");

    private Audit() {
    }

    public static void record(HttpServletRequest request, String operation, String actor, Long promotionId,
                              String target, String result) {
        LoggingEventBuilder event = log.atInfo()
                .addKeyValue("operation", operation)
                .addKeyValue("actor", actor == null ? request.getHeader(ACTOR_HEADER) : actor)
                .addKeyValue("target", target)
                .addKeyValue("result", result);
        if (promotionId != null) {
            event = event.addKeyValue("promotion_id", promotionId);
        }
        Object client = request.getAttribute(CLIENT_ATTRIBUTE);
        if (client != null) {
            event = event.addKeyValue("client", client);
        }
        event.log("{} {} -> {}", operation, target, result);
    }
}
