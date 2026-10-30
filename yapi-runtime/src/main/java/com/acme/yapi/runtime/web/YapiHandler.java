package com.acme.yapi.runtime.web;

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
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.acme.yapi.core.alarm.AlarmValues;
import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.logging.LogEvent;
import com.acme.yapi.core.naming.Names;
import com.acme.yapi.core.plugin.Plugins;
import com.acme.yapi.core.logging.Masker;
import com.acme.yapi.core.request.BackendRequest;
import com.acme.yapi.core.request.BackendRequestBuilder;
import com.acme.yapi.core.request.RequestBodyValidator;
import com.acme.yapi.core.request.RequestBuildException;
import com.acme.yapi.core.request.RequestContext;
import com.acme.yapi.core.request.RequestScope;
import com.acme.yapi.core.response.ClientResponse;
import com.acme.yapi.core.response.Outcome;
import com.acme.yapi.core.response.ResponseProfile;
import com.acme.yapi.core.route.AlarmRoute;
import com.acme.yapi.core.route.AlarmSpec;
import com.acme.yapi.core.route.RouteDefinition;
import com.acme.yapi.core.route.RouteValidator;
import com.acme.yapi.core.validation.FieldError;
import com.acme.yapi.runtime.config.YapiProperties;
import com.acme.yapi.runtime.forward.Forwarder;
import com.acme.yapi.runtime.forward.TraceContext;
import com.acme.yapi.runtime.forward.UserContextSigner;
import com.acme.yapi.runtime.log.LogGate;
import com.acme.yapi.runtime.metrics.YapiMetrics;
import com.acme.yapi.runtime.route.Route;
import com.acme.yapi.runtime.route.RouteCache;
import com.acme.yapi.runtime.route.RouteSnapshot;
import com.acme.yapi.runtime.session.ApplicationList;
import com.acme.yapi.runtime.session.SessionStore;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The request flow of the design document ("Broker'da istek işleme") with the legacy broker's order: body and
 * {@code X-Token}, application, session operations (login/logout to the login service), route and session. Every exit
 * goes through {@link #finish}, which returns nothing to the frontend before the events of the request are
 * acknowledged.
 */
@Component
public class YapiHandler {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    static final String TOKEN_HEADER = "X-Token";

    private static final Logger log = LoggerFactory.getLogger(YapiHandler.class);
    /** Headers of a file response passed on to the frontend. */
    private static final List<String> FILE_HEADERS = List.of("Content-Type", "Content-Disposition", "Content-Length",
            "Cache-Control", "ETag", "Last-Modified");

    private final RouteCache routes;
    private final SessionStore sessions;
    private final LogGate gate;
    private final EventFactory events;
    private final Forwarder forwarder;
    private final UserContextSigner userContext;
    private final YapiMetrics metrics;
    private final RouteValidator.Context readable;
    private final YapiProperties.Forward forward;
    private final YapiProperties.Session sessionSettings;
    private final ApplicationList applications;
    private final boolean legacyResponses;
    private final String environment;
    private final java.time.ZoneId timeZone;

    public YapiHandler(RouteCache routes, SessionStore sessions, ApplicationList applications, LogGate gate,
                         EventFactory events, Forwarder forwarder, UserContextSigner userContext, YapiMetrics metrics,
                         YapiProperties properties) {
        this.routes = routes;
        this.sessions = sessions;
        this.applications = applications;
        this.sessionSettings = properties.session();
        this.legacyResponses = properties.compat().legacyResponses();
        this.gate = gate;
        this.events = events;
        this.forwarder = forwarder;
        this.userContext = userContext;
        this.metrics = metrics;
        this.readable = RouteValidator.Context.of(properties.readableHeaders());
        this.environment = properties.environment();
        this.timeZone = properties.timeZone();
        this.forward = properties.forward();
    }

    /**
     * A response decided by the broker, written only after the events are acknowledged; {@code headers} are the
     * plugins' response headers.
     */
    private record Reply(int status, Outcome outcome, String contentType, byte[] body, Map<String, String> headers) {

        Reply(int status, Outcome outcome, String contentType, byte[] body) {
            this(status, outcome, contentType, body, Map.of());
        }

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
        boolean sessionToken = sessions.isSessionOperation(x.token);
        RouteSnapshot snapshot = routes.snapshot();
        // alarm service routes are called by the broker only; for frontends they do not exist
        Optional<Route> found = snapshot.find(x.maintask, x.subtask).filter(route -> !route.definition().isAlarmService());
        x.knownProject = snapshot.project(x.maintask).orElse(null);
        if (!sessionToken) {
            metrics.legacyIncoming(found.map(Route::maintask).orElse(x.knownProject),
                    found.map(Route::subtask).orElse(null));
        }

        // 3. Application: login, logout ve anonim route'lar dahil her istekte
        if (!applications.allows(x.application)) {
            if (!sessionToken || sessionSettings.isPublic(x.maintask)) {
                denied(x, snapshot, LogEvent.DeniedReason.APPLICATION, found.orElse(null));
            }
            if (legacyResponses) {
                legacy(x, response, YapiErrors.legacyRefused());
            } else {
                reject(x, response, 403, Outcome.DENIED, "Uygulama bu işlemi kullanamaz: " + x.application, List.of());
            }
            return;
        }

        // 4. Oturum işlemleri: gövdedeki maintask'a bakılmadan giriş servisinin route'una
        if (sessionToken && !sessionSettings.isPublic(x.maintask)) {
            x.sessionOperation = x.token;
            String target = sessionSettings.routes().get(x.token);
            int slash = target.indexOf('/');
            Optional<Route> operation = slash < 0 ? Optional.empty()
                    : snapshot.find(target.substring(0, slash), target.substring(slash + 1));
            if (operation.isEmpty() || !operation.get().anonymous()) {
                log.error("Oturum işlemi yapılandırılmamış: X-Token {} için {} route'u {} (requestId={})", x.token,
                        target, operation.isEmpty() ? "yok" : "anonim değil", x.requestId);
                reject(x, response, 503, Outcome.TECHNICAL_ERROR, "Oturum işlemi şu an yapılamıyor.", List.of());
                return;
            }
            x.route = operation.get();
            forward(x, request, response);
            return;
        }

        // 5. Route; yoksa önce oturum, böylece oturumsuz biri hangi route'ların olduğunu göremez
        if (found.isEmpty()) {
            switch (checkSession(x)) {
                case UNAVAILABLE -> sessionUnavailable(x, response);
                case INVALID -> {
                    denied(x, snapshot, LogEvent.DeniedReason.SESSION, null);
                    sessionRefused(x, response);
                }
                case VALID -> {
                    String name = (x.maintask == null ? "?" : x.maintask) + "/" + (x.subtask == null ? "?" : x.subtask);
                    reject(x, response, 404, Outcome.UNKNOWN_TASK, "Bilinmeyen işlem: " + name, List.of());
                }
            }
            return;
        }
        Route route = found.get();
        x.route = route;
        SessionCheck session = checkSession(x);
        if (!route.anonymous()) {
            // anonim route oturum geçersizse ya da okunamazsa kullanıcısız devam eder
            if (session == SessionCheck.UNAVAILABLE) {
                sessionUnavailable(x, response);
                return;
            }
            if (session == SessionCheck.INVALID) {
                denied(x, snapshot, LogEvent.DeniedReason.SESSION, route);
                sessionRefused(x, response);
                return;
            }
            if (!route.allows(x.session.permissions())) {
                denied(x, snapshot, LogEvent.DeniedReason.PERMISSION, route);
                reject(x, response, 403, Outcome.DENIED, "Bu işlem için yetkiniz yok.", List.of());
                return;
            }
        }
        forward(x, request, response);
    }

    private enum SessionCheck { VALID, INVALID, UNAVAILABLE }

    /**
     * Marks the request as refused for the RESPONSE event. Whether the user holds a permission of the route's project
     * is told only with a route, a verified user and the project's prefix.
     */
    private static void denied(Exchange x, RouteSnapshot snapshot, LogEvent.DeniedReason reason, Route route) {
        x.deniedReason = reason;
        x.deniedRoute = route;
        if (route != null && x.session != null) {
            String prefix = snapshot.projectOf(route.maintask()).map(RouteSnapshot.Project::permissionPrefix)
                    .orElse(null);
            x.hasProjectPermission = prefix == null ? null
                    : x.session.permissionList().stream().anyMatch(permission -> Names.belongsTo(permission, prefix));
        }
    }

    /** One Redis read: the record of the body's uid, valid when the token and client headers match it. */
    private SessionCheck checkSession(Exchange x) {
        try {
            x.session = sessions.find(x.uid, x.token, x.clientIp, x.userAgent).orElse(null);
            return x.session == null ? SessionCheck.INVALID : SessionCheck.VALID;
        } catch (RuntimeException e) {
            log.error("Oturum Redis'ten okunamadı (requestId={})", x.requestId, e);
            return SessionCheck.UNAVAILABLE;
        }
    }

    private void sessionRefused(Exchange x, HttpServletResponse response) throws IOException {
        if (legacyResponses) {
            legacy(x, response, YapiErrors.legacySessionInvalid());
        } else {
            reject(x, response, 401, Outcome.DENIED, "Oturum bulunamadı; lütfen yeniden giriş yapın.", List.of());
        }
    }

    private void sessionUnavailable(Exchange x, HttpServletResponse response) throws IOException {
        reject(x, response, 503, Outcome.TECHNICAL_ERROR, "Oturum doğrulanamadı; lütfen tekrar deneyin.", List.of());
    }

    /** Steps 5-7 for a request whose route and session are settled. */
    private void forward(Exchange x, HttpServletRequest request, HttpServletResponse response) throws IOException {
        Route route = x.route;
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
        String permission = x.session == null ? null
                : RequestContext.firstPermission(route.permissions(), x.session.permissions());
        RequestScope scope = new RequestScope(environment, x.trace.traceId(), route.maintask(), route.subtask(),
                route.version(), x.clientIp, x.userAgent, x.session == null ? List.of() : x.session.permissionList(),
                x.receivedAt, timeZone, null);
        RequestContext context = new RequestContext(x.body, validated.effectiveBody(), request::getHeader, readable,
                userId, permission, x.requestId).withScope(scope);
        x.context = context;
        BackendRequest backendRequest;
        try {
            backendRequest = BackendRequestBuilder.build(route.method(), route.pathTemplate(), definition,
                    route.baseUrl(), context);
        } catch (RequestBuildException e) {
            reject(x, response, 400, Outcome.INVALID_REQUEST, "İstek gövdesi alan tanımına uymuyor.",
                    List.of(e.error()));
            return;
        }
        // plugins: not for login and logout, whose bodies are the legacy ones
        if (x.sessionOperation == null) {
            x.plugins = routes.snapshot().pluginsOf(route);
            Plugins.RequestResult changed = Plugins.applyRequest(backendRequest, x.plugins, definition.request(),
                    context.resolver());
            backendRequest = changed.request();
            x.appliedPlugins.addAll(changed.applied());
        }
        x.targetPath = backendRequest.uri().getRawPath();

        // 6. Alarm kontrolü: servisten önce, alarm servisi route'unun kendi timeout'uyla
        if (x.sessionOperation == null && !alarmPassed(x, response, context, userId)) {
            return;
        }

        // 6. Forward
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("traceparent", x.trace.traceparent());
        // every call is signed, without a user when there is no valid session (session operations, anonymous
        // routes): services that check the signature accept only the broker's calls and tell who the user is
        if (userContext.enabled()) {
            extra.put(userContext.header(), userContext.sign(userId, x.requestId, Instant.now()));
        }
        long sent = System.nanoTime();
        Forwarder.Result result = forwarder.send(backendRequest, definition.timeoutMs(), definition.maxAttempts(), extra);
        x.backendDurationMs = (System.nanoTime() - sent) / 1_000_000;
        switch (result) {
            case Forwarder.TimedOut timedOut -> backendFailed(x, response, Outcome.TIMEOUT, 504,
                    "Servis zamanında yanıt vermedi.", "Servis " + definition.timeoutMs() + " ms içinde yanıt vermedi");
            case Forwarder.Failed failed -> backendFailed(x, response, Outcome.TECHNICAL_ERROR, 502,
                    "Servise ulaşılamadı.", "Servise ulaşılamadı: " + failed.error());
            case Forwarder.Responded responded -> backendResponded(x, response, responded.response());
        }
    }

    /**
     * The subtask's alarm check ({@code alarm} of its definition; {@code INHERIT} takes the project's
     * {@code alarmRoute}): the request's values go to the alarm service route and the request stops here when the
     * answer lists any, or when there is no usable answer. True when the service may be called.
     */
    private boolean alarmPassed(Exchange x, HttpServletResponse response, RequestContext context, String userId)
            throws IOException {
        Optional<AlarmRoute> target = alarmRouteOf(x.route);
        if (target.isEmpty()) {
            return true;
        }
        x.alarm = Json.object();
        x.alarm.put("maintask", target.get().maintask());
        x.alarm.put("subtask", target.get().subtask());
        Optional<Route> service = routes.find(target.get().maintask(), target.get().subtask())
                .filter(route -> route.definition().isAlarmService());
        if (service.isEmpty()) {
            log.error("Alarm servisi route'u ortamda yok ya da alarm servisi değil: {}/{} (requestId={})",
                    target.get().maintask(), target.get().subtask(), x.requestId);
            return alarmFailed(x, response, "Alarm servisi route'u yok: " + target.get().maintask() + "/"
                    + target.get().subtask());
        }
        Route alarmRoute = service.get();
        RouteDefinition definition = alarmRoute.definition();
        Set<String> masked = x.route.definition().logging().effectiveMaskFields();
        BackendRequest alarmRequest;
        try {
            alarmRequest = BackendRequestBuilder.build(alarmRoute.method(), alarmRoute.pathTemplate(), definition,
                    alarmRoute.baseUrl(), context.withAlarmValues(AlarmValues.of(x.body, masked)));
        } catch (RequestBuildException e) {
            return alarmFailed(x, response, "Alarm isteği kurulamadı: " + e.error().message());
        }
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("traceparent", x.trace.traceparent());
        if (userContext.enabled() && userId != null) {
            extra.put(userContext.header(), userContext.sign(userId, x.requestId, Instant.now()));
        }
        Forwarder.Result result = forwarder.send(alarmRequest, definition.timeoutMs(), 1, extra);
        JsonNode answer;
        switch (result) {
            case Forwarder.TimedOut timedOut -> {
                return alarmFailed(x, response, "Alarm servisi " + definition.timeoutMs() + " ms içinde yanıt vermedi");
            }
            case Forwarder.Failed failed -> {
                return alarmFailed(x, response, "Alarm servisine ulaşılamadı: " + failed.error());
            }
            case Forwarder.Responded responded -> {
                HttpResponse<InputStream> reply = responded.response();
                BackendResponses.Body body;
                try {
                    body = BackendResponses.read(reply, forward.maxResponseBytes(),
                            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(definition.timeoutMs()));
                } catch (TimeoutException | IOException e) {
                    return alarmFailed(x, response, "Alarm servisinin yanıtı okunamadı: " + e);
                }
                if (reply.statusCode() < 200 || reply.statusCode() >= 300) {
                    return alarmFailed(x, response, "Alarm servisi " + reply.statusCode() + " döndü");
                }
                answer = body.tooLarge() ? null : Json.tryParse(body.bytes());
            }
        }
        Optional<List<String>> alarms = AlarmValues.alarms(answer, definition.alarmService().resultPath());
        if (alarms.isEmpty()) {
            return alarmFailed(x, response, "Alarm servisinin yanıtında alarm listesi yok");
        }
        if (alarms.get().isEmpty()) {
            x.alarm = null;
            return true;
        }
        x.alarm.set("alarms", Json.stringArray(alarms.get()));
        x.alarm.set("params", Masker.mask(x.body, masked));
        log.warn("İstek alarm kontrolüne takıldı: {}/{}, uid={}, alarm={} (requestId={})", x.route.maintask(),
                x.route.subtask(), x.uid, alarms.get().size(), x.requestId);
        if (legacyResponses) {
            ObjectNode legacy = YapiErrors.legacyAlarm();
            finish(x, response, Reply.json(200, Outcome.ALARM, legacy),
                    events.response(x, 200, Outcome.ALARM, null, null, EventFactory.legacyLogged(legacy), null));
        } else {
            ObjectNode error = YapiErrors.body(403, Outcome.ALARM, "İstek alarm kontrolüne takıldı; servis çağrılmadı.",
                    x.requestId, List.of());
            finish(x, response, Reply.json(403, Outcome.ALARM, error),
                    events.response(x, 403, Outcome.ALARM, null, null, error, null));
        }
        return false;
    }

    private Optional<AlarmRoute> alarmRouteOf(Route route) {
        if (route.definition().isAlarmService()) {
            return Optional.empty();
        }
        AlarmSpec alarm = route.definition().alarm();
        return switch (alarm.check()) {
            case OFF -> Optional.empty();
            case ROUTE -> Optional.of(new AlarmRoute(alarm.maintask(), alarm.subtask()));
            case INHERIT -> routes.snapshot().projectOf(route.maintask())
                    .map(RouteSnapshot.Project::alarmRoute);
        };
    }

    /** The check could not be made: the request is refused, the service is not called. */
    private boolean alarmFailed(Exchange x, HttpServletResponse response, String error) throws IOException {
        x.alarm.put("error", error);
        log.error("Alarm kontrolü yapılamadı: {} (requestId={})", error, x.requestId);
        ObjectNode body = YapiErrors.body(503, Outcome.TECHNICAL_ERROR, "Alarm kontrolü yapılamadı; istek gönderilmedi.",
                x.requestId, List.of());
        finish(x, response, Reply.json(503, Outcome.TECHNICAL_ERROR, body),
                events.response(x, 503, Outcome.TECHNICAL_ERROR, null, null, body, error));
        return false;
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
        Map<String, String> backendHeaders = BackendResponses.profileHeaders(backend);
        if (route.profile() != null) {
            ClientResponse wrapped = route.profile().profile()
                    .render(new ResponseProfile.BackendResult(status, parsed, outcome, x.requestId, backendHeaders,
                            x.context.scope()));
            Plugins.ResponseResult changed = responsePlugins(x, Map.of(), wrapped.body(), status, backendHeaders,
                    outcome);
            reply = new Reply(wrapped.status(), outcome, "application/json", Json.writeBytes(changed.body()),
                    changed.headers());
            clientLog = EventFactory.clientResponse(changed.body(), definition.logging());
        } else if (x.sessionOperation != null) {
            reply = new Reply(status, outcome, contentType, body.bytes());
            clientLog = EventFactory.sessionResponse(status, parsed);
        } else {
            // a body is changed only when it is a JSON object; anything else goes out as the service sent it
            boolean object = parsed != null && parsed.isObject();
            Plugins.ResponseResult changed = responsePlugins(x, Map.of(), object ? parsed : null, status,
                    backendHeaders, outcome);
            byte[] bytes = object && !changed.body().equals(parsed) ? Json.writeBytes(changed.body()) : body.bytes();
            reply = new Reply(status, outcome, contentType, bytes, changed.headers());
            clientLog = EventFactory.passedThrough(status, object ? changed.body() : parsed, definition.logging());
        }
        finish(x, response, reply, events.response(x, status, outcome, logged, body.bytes().length, clientLog, null));
    }

    /** The plugins' response operations on what the frontend gets; {@code body} null for files and other bodies. */
    private Plugins.ResponseResult responsePlugins(Exchange x, Map<String, String> headers, JsonNode body,
                                                   Integer status, Map<String, String> backendHeaders,
                                                   Outcome outcome) {
        if (x.plugins.isEmpty() || x.context == null) {
            return new Plugins.ResponseResult(headers, body, List.of());
        }
        Plugins.ResponseValues values = new Plugins.ResponseValues(status, backendHeaders, outcome.name(),
                x.backendDurationMs, x.receivedAt, x.context.scope());
        Plugins.ResponseResult changed = Plugins.applyResponse(headers, body, x.plugins,
                Plugins.responseResolver(x.context.resolver(), values));
        x.appliedPlugins.addAll(changed.applied());
        return changed;
    }

    /** Status and headers arrived: log first, then stream; the body is never buffered, parsed or logged. */
    private void streamFile(Exchange x, HttpServletResponse response, HttpResponse<InputStream> backend, int status,
                            Outcome outcome) throws IOException {
        ObjectNode info = Json.object();
        info.put("info", BackendResponses.fileInfo(x.route.definition().logging(), backend));
        Integer size = backend.headers().firstValueAsLong("Content-Length").stream()
                .mapToObj(length -> (int) Math.min(length, Integer.MAX_VALUE)).findFirst().orElse(null);
        Map<String, String> fileHeaders = new LinkedHashMap<>();
        for (String header : FILE_HEADERS) {
            backend.headers().firstValue(header).ifPresent(value -> fileHeaders.put(header, value));
        }
        // a file gets the plugins' header operations only; Content-Length stays the service's
        Map<String, String> headers = responsePlugins(x, fileHeaders, null, status,
                BackendResponses.profileHeaders(backend), outcome).headers();
        LogEvent event = events.response(x, status, outcome, null, size, info, null);
        List<LogEvent> unacknowledged = gate.write(event);
        try (InputStream body = backend.body()) {
            if (!unacknowledged.isEmpty()) {
                logUnavailable(x, response, unacknowledged);
                return;
            }
            response.setStatus(status);
            headers.forEach(response::setHeader);
            backend.headers().firstValue("Content-Length").ifPresent(value -> response.setHeader("Content-Length", value));
            try (OutputStream out = response.getOutputStream()) {
                body.transferTo(out);
            }
        }
        record(x, outcome);
    }

    /** Timeout or connection failure: the profile's error template if there is one, else the broker's format. */
    private void backendFailed(Exchange x, HttpServletResponse response, Outcome outcome, int yapiStatus,
                               String message, String errorMessage) throws IOException {
        Reply reply;
        EventFactory.ClientLog clientLog;
        if (x.route.profile() != null) {
            ClientResponse wrapped = x.route.profile().profile()
                    .render(new ResponseProfile.BackendResult(null, null, outcome, x.requestId, Map.of(),
                            x.context == null ? null : x.context.scope()));
            // the service was called and did not answer: the profile's envelope gets the plugins too
            Plugins.ResponseResult changed = responsePlugins(x, Map.of(), wrapped.body(), null, Map.of(), outcome);
            reply = new Reply(wrapped.status(), outcome, "application/json", Json.writeBytes(changed.body()),
                    changed.headers());
            clientLog = EventFactory.clientResponse(changed.body(), x.route.definition().logging());
        } else {
            ObjectNode error = YapiErrors.body(yapiStatus, outcome, message, x.requestId, List.of());
            reply = Reply.json(yapiStatus, outcome, error);
            clientLog = EventFactory.ClientLog.of(error);
        }
        // no backend status: the event carries the status the frontend got, so status_code is never empty
        finish(x, response, reply, events.response(x, reply.status(), outcome, null, null, clientLog, errorMessage));
    }

    /**
     * The legacy broker's HTTP 200 bodies ({@code {"response": false}} and the 301 body), logged as it logged every
     * response: the body with {@code "data": ""} added.
     */
    private void legacy(Exchange x, HttpServletResponse response, ObjectNode body) throws IOException {
        finish(x, response, Reply.json(200, Outcome.DENIED, body),
                events.response(x, 200, Outcome.DENIED, null, null, EventFactory.legacyLogged(body), null));
    }

    /** Errors the broker gives without calling the backend, in the common format. */
    private void reject(Exchange x, HttpServletResponse response, int status, Outcome outcome, String message,
                        List<FieldError> errors) throws IOException {
        ObjectNode body = YapiErrors.body(status, outcome, message, x.requestId, errors);
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
        reply.headers().forEach(response::setHeader);
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
        byte[] body = Json.writeBytes(YapiErrors.logUnavailableBody());
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
