package com.acme.broker.runtime.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.acme.broker.core.json.Json;
import com.acme.broker.core.logging.LogEvent;
import com.acme.broker.core.request.BackendRequest;
import com.acme.broker.core.request.BackendRequestBuilder;
import com.acme.broker.core.request.RequestBodyValidator;
import com.acme.broker.core.request.RequestBuildException;
import com.acme.broker.core.request.RequestContext;
import com.acme.broker.core.response.ClientResponse;
import com.acme.broker.core.response.Outcome;
import com.acme.broker.core.response.ResponseProfile;
import com.acme.broker.core.route.RouteDefinition;
import com.acme.broker.core.route.RouteValidator;
import com.acme.broker.core.validation.FieldError;
import com.acme.broker.runtime.config.BrokerProperties;
import com.acme.broker.runtime.forward.Forwarder;
import com.acme.broker.runtime.forward.TraceContext;
import com.acme.broker.runtime.forward.UserContextSigner;
import com.acme.broker.runtime.log.LogGate;
import com.acme.broker.runtime.metrics.BrokerMetrics;
import com.acme.broker.runtime.route.Route;
import com.acme.broker.runtime.route.RouteCache;
import com.acme.broker.runtime.route.RouteSnapshot;
import com.acme.broker.runtime.session.Session;
import com.acme.broker.runtime.session.SessionStore;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The request flow of the design document ("Broker'da istek işleme"). Every exit goes through {@link #finish}, which
 * returns nothing to the frontend before the events of the request are acknowledged.
 */
@Component
public class BrokerHandler {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    static final String TOKEN_HEADER = "X-Token";

    private static final Logger log = LoggerFactory.getLogger(BrokerHandler.class);
    /** Headers of a file response passed on to the frontend. */
    private static final List<String> FILE_HEADERS = List.of("Content-Type", "Content-Disposition", "Content-Length",
            "Cache-Control", "ETag", "Last-Modified");

    private final RouteCache routes;
    private final SessionStore sessions;
    private final LogGate gate;
    private final EventFactory events;
    private final Forwarder forwarder;
    private final UserContextSigner userContext;
    private final BrokerMetrics metrics;
    private final RouteValidator.Context readable;
    private final BrokerProperties.Forward forward;

    public BrokerHandler(RouteCache routes, SessionStore sessions, LogGate gate, EventFactory events,
                         Forwarder forwarder, UserContextSigner userContext, BrokerMetrics metrics,
                         BrokerProperties properties) {
        this.routes = routes;
        this.sessions = sessions;
        this.gate = gate;
        this.events = events;
        this.forwarder = forwarder;
        this.userContext = userContext;
        this.metrics = metrics;
        this.readable = RouteValidator.Context.of(properties.readableHeaders());
        this.forward = properties.forward();
    }

    /** A response decided by the broker, written only after the events are acknowledged. */
    private record Reply(int status, Outcome outcome, String contentType, byte[] body) {

        static Reply json(int status, Outcome outcome, JsonNode body) {
            return new Reply(status, outcome, "application/json", Json.writeBytes(body));
        }
    }

    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Exchange x = new Exchange(TraceContext.from(request.getHeader("traceparent")), request.getHeader(TOKEN_HEADER),
                request.getHeader("X-Clientip"), request.getHeader("X-User-Agent"));
        response.setHeader(REQUEST_ID_HEADER, x.requestId);
        try {
            process(x, request, response);
        } catch (RuntimeException e) {
            log.error("İstek işlenirken beklenmeyen hata (requestId={})", x.requestId, e);
            if (!response.isCommitted()) {
                reject(x, response, 500, Outcome.TECHNICAL_ERROR, "Beklenmeyen bir hata oluştu.", List.of());
            }
        }
    }

    private void process(Exchange x, HttpServletRequest request, HttpServletResponse response) throws IOException {
        // 1-2. Karşılama: gövde ve X-Token
        byte[] raw = request.getInputStream().readNBytes(forward.maxRequestBytes() + 1);
        if (raw.length > forward.maxRequestBytes()) {
            reject(x, response, 400, Outcome.INVALID_REQUEST, "İstek gövdesi çok büyük.", List.of());
            return;
        }
        x.rawBody = raw;
        JsonNode parsed = Json.tryParse(raw);
        if (parsed == null || !parsed.isObject()) {
            reject(x, response, 400, Outcome.INVALID_REQUEST, "Gövde bir JSON nesnesi olmalı.", List.of());
            return;
        }
        x.body = (ObjectNode) parsed;
        x.maintask = Json.stringField(x.body, "maintask");
        x.subtask = Json.stringField(x.body, "subtask");
        x.application = Json.text(x.body.get("application"));
        x.uid = Json.text(x.body.get("uid"));
        if (x.token == null) {
            reject(x, response, 400, Outcome.INVALID_REQUEST, "X-Token header'ı zorunlu.", List.of());
            return;
        }
        x.sessionOperation = sessions.isSessionOperation(x.token) ? x.token : null;

        // 3. Route çözümleme: yalnızca bellek
        RouteSnapshot snapshot = routes.snapshot();
        Optional<Route> found = snapshot.find(x.maintask, x.subtask);
        x.knownProject = snapshot.project(x.maintask).orElse(null);
        if (x.sessionOperation == null) {
            metrics.legacyIncoming(found.map(Route::maintask).orElse(x.knownProject),
                    found.map(Route::subtask).orElse(null));
        }
        if (found.isEmpty()) {
            String name = (x.maintask == null ? "?" : x.maintask) + "/" + (x.subtask == null ? "?" : x.subtask);
            reject(x, response, 404, Outcome.UNKNOWN_TASK, "Bilinmeyen işlem: " + name, List.of());
            return;
        }
        Route route = found.get();
        x.route = route;

        // 4. Oturum ve yetki: tek Redis okuması
        if (x.sessionOperation != null) {
            if (!route.anonymous()) {
                reject(x, response, 403, Outcome.DENIED, "Oturum işlemi yalnızca anonim bir route ile yapılabilir.",
                        List.of());
                return;
            }
        } else {
            Optional<Session> session;
            try {
                session = sessions.find(x.token);
            } catch (RuntimeException e) {
                log.error("Oturum Redis'ten okunamadı (requestId={})", x.requestId, e);
                if (!route.anonymous()) {
                    reject(x, response, 503, Outcome.TECHNICAL_ERROR, "Oturum doğrulanamadı; lütfen tekrar deneyin.",
                            List.of());
                    return;
                }
                session = Optional.empty(); // an anonymous route works without a user, as with an invalid token
            }
            x.session = session.orElse(null);
            if (!route.anonymous()) {
                if (x.session == null) {
                    reject(x, response, 401, Outcome.DENIED, "Oturum bulunamadı; lütfen yeniden giriş yapın.",
                            List.of());
                    return;
                }
                if (!route.allows(x.session.permissions())) {
                    reject(x, response, 403, Outcome.DENIED, "Bu işlem için yetkiniz yok.", List.of());
                    return;
                }
            }
        }

        // 5. REQUEST olayı: onay gelmeden servis çağrılmaz
        LogEvent requestEvent = events.request(x);
        List<LogEvent> unacknowledged = gate.write(requestEvent);
        x.requestLogged = true;
        if (!unacknowledged.isEmpty()) {
            logUnavailable(x, response, unacknowledged);
            return;
        }

        // 6. Doğrulama ve istek üretimi
        RouteDefinition definition = route.definition();
        RequestBodyValidator.Result validated = RequestBodyValidator.validate(x.body, definition.request().fields());
        if (!validated.valid()) {
            reject(x, response, 400, Outcome.INVALID_REQUEST, "İstek gövdesi alan tanımına uymuyor.",
                    validated.errors());
            return;
        }
        String userId = x.session == null ? null : x.session.userId();
        BackendRequest backendRequest;
        try {
            backendRequest = BackendRequestBuilder.build(route.method(), route.pathTemplate(), definition,
                    route.baseUrl(), new RequestContext(x.body, validated.effectiveBody(), request::getHeader,
                            readable, userId, x.requestId));
        } catch (RequestBuildException e) {
            reject(x, response, 400, Outcome.INVALID_REQUEST, "İstek gövdesi alan tanımına uymuyor.",
                    List.of(e.error()));
            return;
        }
        x.targetPath = backendRequest.uri().getRawPath();

        // 6. Forward
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("traceparent", x.trace.traceparent());
        if (userContext.enabled() && userId != null) {
            extra.put(userContext.header(), userContext.sign(userId, x.requestId, Instant.now()));
        }
        Forwarder.Result result = forwarder.send(backendRequest, definition.timeoutMs(), definition.maxAttempts(), extra);
        switch (result) {
            case Forwarder.TimedOut timedOut -> backendFailed(x, response, Outcome.TIMEOUT, 504,
                    "Servis zamanında yanıt vermedi.", "Servis " + definition.timeoutMs() + " ms içinde yanıt vermedi");
            case Forwarder.Failed failed -> backendFailed(x, response, Outcome.TECHNICAL_ERROR, 502,
                    "Servise ulaşılamadı.", "Servise ulaşılamadı: " + failed.error());
            case Forwarder.Responded responded -> backendResponded(x, response, responded.response());
        }
    }

    // 7-8. Servis yanıtı: outcome gerçek status'tan, sonra zarf
    private void backendResponded(Exchange x, HttpServletResponse response, HttpResponse<InputStream> backend)
            throws IOException {
        Route route = x.route;
        RouteDefinition definition = route.definition();
        int status = backend.statusCode();
        Outcome outcome = Outcome.fromBackendStatus(status);

        if (BackendResponses.isFile(definition.responseType(), backend)) {
            streamFile(x, response, backend, status, outcome);
            return;
        }
        BackendResponses.Body body;
        try {
            long deadline = x.startedNanos + TimeUnit.MILLISECONDS.toNanos(definition.timeoutMs());
            body = BackendResponses.read(backend, forward.maxResponseBytes(), deadline);
        } catch (TimeoutException e) {
            backendFailed(x, response, Outcome.TIMEOUT, 504, "Servis zamanında yanıt vermedi.",
                    "Servisin yanıt gövdesi " + definition.timeoutMs() + " ms içinde gelmedi");
            return;
        } catch (IOException e) {
            backendFailed(x, response, Outcome.TECHNICAL_ERROR, 502, "Servise ulaşılamadı.",
                    "Servisin yanıtı okunamadı: " + e);
            return;
        }
        if (body.tooLarge()) {
            backendFailed(x, response, Outcome.TECHNICAL_ERROR, 502, "Servisin yanıtı çok büyük.",
                    "Servisin yanıtı " + forward.maxResponseBytes() + " byte sınırını aştı");
            return;
        }
        String contentType = backend.headers().firstValue("Content-Type").orElse(null);
        JsonNode parsed = BackendResponses.parse(body.bytes(), contentType, definition.responseType());
        JsonNode logged = definition.logging().logResponseBody() ? parsed : null;

        Reply reply;
        EventFactory.ClientLog clientLog;
        if (route.profile() != null) {
            ClientResponse wrapped = route.profile().profile()
                    .render(new ResponseProfile.BackendResult(status, parsed, outcome, x.requestId));
            reply = Reply.json(wrapped.status(), outcome, wrapped.body());
            clientLog = EventFactory.clientResponse(wrapped.body(), definition.logging());
        } else {
            reply = new Reply(status, outcome, contentType, body.bytes());
            clientLog = EventFactory.passedThrough(status, parsed, definition.logging());
        }
        finish(x, response, reply, events.response(x, status, outcome, logged, body.bytes().length, clientLog, null));
    }

    /** Status and headers arrived: log first, then stream; the body is never buffered, parsed or logged. */
    private void streamFile(Exchange x, HttpServletResponse response, HttpResponse<InputStream> backend, int status,
                            Outcome outcome) throws IOException {
        ObjectNode info = Json.object();
        info.put("info", BackendResponses.fileInfo(x.route.definition().logging(), backend));
        Integer size = backend.headers().firstValueAsLong("Content-Length").stream()
                .mapToObj(length -> (int) Math.min(length, Integer.MAX_VALUE)).findFirst().orElse(null);
        LogEvent event = events.response(x, status, outcome, null, size, info, null);
        List<LogEvent> unacknowledged = gate.write(event);
        try (InputStream body = backend.body()) {
            if (!unacknowledged.isEmpty()) {
                logUnavailable(x, response, unacknowledged);
                return;
            }
            response.setStatus(status);
            for (String header : FILE_HEADERS) {
                backend.headers().firstValue(header).ifPresent(value -> response.setHeader(header, value));
            }
            try (OutputStream out = response.getOutputStream()) {
                body.transferTo(out);
            }
        }
        record(x, outcome);
    }

    /** Timeout or connection failure: the profile's error template if there is one, else the broker's format. */
    private void backendFailed(Exchange x, HttpServletResponse response, Outcome outcome, int brokerStatus,
                               String message, String errorMessage) throws IOException {
        Reply reply;
        EventFactory.ClientLog clientLog;
        if (x.route.profile() != null) {
            ClientResponse wrapped = x.route.profile().profile()
                    .render(new ResponseProfile.BackendResult(null, null, outcome, x.requestId));
            reply = Reply.json(wrapped.status(), outcome, wrapped.body());
            clientLog = EventFactory.clientResponse(wrapped.body(), x.route.definition().logging());
        } else {
            ObjectNode error = BrokerErrors.body(brokerStatus, outcome, message, x.requestId, List.of());
            reply = Reply.json(brokerStatus, outcome, error);
            clientLog = EventFactory.ClientLog.of(error);
        }
        // no backend status: the event carries the status the frontend got, so status_code is never empty
        finish(x, response, reply, events.response(x, reply.status(), outcome, null, null, clientLog, errorMessage));
    }

    /** Errors the broker gives without calling the backend, in the common format. */
    private void reject(Exchange x, HttpServletResponse response, int status, Outcome outcome, String message,
                        List<FieldError> errors) throws IOException {
        ObjectNode body = BrokerErrors.body(status, outcome, message, x.requestId, errors);
        finish(x, response, Reply.json(status, outcome, body), events.response(x, status, outcome, null, null, body, null));
    }

    /** Writes the RESPONSE event (and the REQUEST event if it was not written yet), then the reply. */
    private void finish(Exchange x, HttpServletResponse response, Reply reply, LogEvent responseEvent)
            throws IOException {
        List<LogEvent> unacknowledged = x.requestLogged
                ? gate.write(responseEvent)
                : gate.write(events.request(x), responseEvent);
        x.requestLogged = true;
        if (!unacknowledged.isEmpty()) {
            logUnavailable(x, response, unacknowledged);
            return;
        }
        response.setStatus(reply.status());
        if (reply.contentType() != null) {
            response.setContentType(reply.contentType());
        }
        response.setContentLength(reply.body().length);
        response.getOutputStream().write(reply.body());
        record(x, reply.outcome());
    }

    /**
     * Kafka did not acknowledge: the frontend gets {@code 500} and {@code {"result": false}} as today, the service's
     * answer (if any) is dropped, and the events go to the spool with outcome LOG_UNAVAILABLE.
     */
    private void logUnavailable(Exchange x, HttpServletResponse response, List<LogEvent> unacknowledged)
            throws IOException {
        List<LogEvent> spooled = new ArrayList<>();
        for (LogEvent event : unacknowledged) {
            if (event.eventType() == LogEvent.EventType.REQUEST) {
                spooled.add(event);
            }
        }
        spooled.add(events.logUnavailable(x));
        gate.spool(spooled);
        byte[] body = Json.writeBytes(BrokerErrors.logUnavailableBody());
        response.setStatus(500);
        response.setContentType("application/json");
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        record(x, Outcome.LOG_UNAVAILABLE);
    }

    private void record(Exchange x, Outcome outcome) {
        metrics.request(x.metricMaintask(), x.metricSubtask(), outcome.name(),
                Duration.ofNanos(System.nanoTime() - x.startedNanos));
    }
}
