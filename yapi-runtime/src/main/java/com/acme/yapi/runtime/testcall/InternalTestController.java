package com.acme.yapi.runtime.testcall;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.acme.yapi.core.ids.Ids;
import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.request.BackendRequest;
import com.acme.yapi.core.request.BackendRequestBuilder;
import com.acme.yapi.core.request.RequestBodyValidator;
import com.acme.yapi.core.request.RequestBuildException;
import com.acme.yapi.core.request.RequestContext;
import com.acme.yapi.core.response.ClientResponse;
import com.acme.yapi.core.response.Outcome;
import com.acme.yapi.core.response.ResponseProfile;
import com.acme.yapi.core.route.HttpMethodName;
import com.acme.yapi.core.route.PathTemplate;
import com.acme.yapi.core.route.RouteDefinition;
import com.acme.yapi.core.route.RouteDefinitionParser;
import com.acme.yapi.core.route.RouteValidator;
import com.acme.yapi.core.testcall.TestCallRequest;
import com.acme.yapi.core.testcall.TestCallResult;
import com.acme.yapi.core.testcall.TestInterpretation;
import com.acme.yapi.core.validation.FieldError;
import com.acme.yapi.runtime.config.YapiProperties;
import com.acme.yapi.runtime.forward.Forwarder;
import com.acme.yapi.runtime.forward.TraceContext;
import com.acme.yapi.runtime.forward.UserContextSigner;
import com.acme.yapi.runtime.web.BackendResponses;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Test calls with an unsaved definition, through the same validator, request builder and forwarder as real traffic:
 * a separate code path would test that code, not the broker. Exists only where {@code yapi.test.enabled} is set
 * (DEV, TEST) and answers only callers with the shared secret, i.e. the environment's Admin API. Test calls are not
 * written to Kafka; the central admin audits them.
 */
@RestController
@ConditionalOnBooleanProperty("yapi.test.enabled")
public class InternalTestController {

    private final Forwarder forwarder;
    private final UserContextSigner userContext;
    private final RouteValidator.Context readable;
    private final byte[] secret;
    private final int maxResponseBytes;

    public InternalTestController(Forwarder forwarder, UserContextSigner userContext, YapiProperties properties) {
        this.forwarder = forwarder;
        this.userContext = userContext;
        this.readable = RouteValidator.Context.of(properties.readableHeaders());
        String configured = properties.test().secret();
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("yapi.test.enabled açıkken yapi.test.secret zorunlu");
        }
        this.secret = configured.getBytes(StandardCharsets.UTF_8);
        this.maxResponseBytes = properties.forward().maxResponseBytes();
    }

    @PostMapping(path = TestCallRequest.PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> test(@RequestHeader(name = TestCallRequest.SECRET_HEADER, required = false) String given,
                                  @RequestBody TestCallRequest call) {
        if (given == null || !MessageDigest.isEqual(secret, given.getBytes(StandardCharsets.UTF_8))) {
            return problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "Test çağrısı için geçerli bir gizli anahtar gerekli.",
                    List.of());
        }
        List<FieldError> errors = new ArrayList<>(RouteValidator.validate(call.httpMethod(), call.pathTemplate(),
                call.definition(), RouteValidator.Context.anyHeader()));
        if (call.baseUrl() == null || call.baseUrl().isBlank()) {
            errors.add(new FieldError("baseUrl", "Zorunlu"));
        }
        ResponseProfile profile = null;
        if (call.responseProfile() != null) {
            ResponseProfile.Parsed parsed = ResponseProfile.parse(call.responseProfile().definition());
            parsed.errors().forEach(error -> errors.add(error.prefixed("responseProfile.definition")));
            profile = parsed.profile();
        }
        if (call.sample() == null || !call.sample().isObject()) {
            errors.add(new FieldError("sample", "Örnek gövde bir JSON nesnesi olmalı"));
        }
        if (!errors.isEmpty()) {
            return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "Tanım geçersiz: " + errors.size() + " alan hatalı.", errors);
        }
        return ResponseEntity.ok(run(call, profile));
    }

    private TestCallResult run(TestCallRequest call, ResponseProfile profile) {
        RouteDefinition definition = RouteDefinitionParser.parse(call.definition()).definition();
        ObjectNode sample = (ObjectNode) call.sample();
        RequestBodyValidator.Result validated = RequestBodyValidator.validate(sample, definition.request().fields());
        if (!validated.valid()) {
            return invalid(validated.errors());
        }
        String requestId = Ids.uuidV7().toString();
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (call.headers() != null) {
            headers.putAll(call.headers());
        }
        BackendRequest request;
        try {
            request = BackendRequestBuilder.build(HttpMethodName.parse(call.httpMethod()).orElseThrow(),
                    PathTemplate.parse(call.pathTemplate()), definition, call.baseUrl(),
                    new RequestContext(sample, validated.effectiveBody(), headers::get, readable, call.userId(),
                            call.permission(), requestId));
        } catch (RequestBuildException e) {
            return invalid(List.of(e.error()));
        }
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("traceparent", TraceContext.from(null).traceparent());
        if (userContext.enabled() && call.userId() != null) {
            extra.put(userContext.header(), userContext.sign(call.userId(), requestId, Instant.now()));
        }
        TestCallResult.RequestView sent = TestCallResult.RequestView.of(request);
        Forwarder.Result result = forwarder.send(request, definition.timeoutMs(), definition.maxAttempts(), extra);
        long started = result.startedNanos();
        return switch (result) {
            case Forwarder.TimedOut timedOut -> failed(sent, Outcome.TIMEOUT, profile, requestId, started);
            case Forwarder.Failed failed -> failed(sent, Outcome.TECHNICAL_ERROR, profile, requestId, started);
            case Forwarder.Responded responded -> responded(sent, responded.response(), definition, profile,
                    requestId, started);
        };
    }

    private TestCallResult responded(TestCallResult.RequestView sent, HttpResponse<InputStream> response,
                                     RouteDefinition definition, ResponseProfile profile, String requestId,
                                     long started) {
        int status = response.statusCode();
        Outcome outcome = Outcome.fromBackendStatus(status);
        Map<String, List<String>> headers = headers(response);
        if (BackendResponses.isFile(definition.responseType(), response)) {
            try (InputStream body = response.body()) {
                body.transferTo(java.io.OutputStream.nullOutputStream());
            } catch (IOException ignored) {
                // the panel shows only the file's description
            }
            ObjectNode info = Json.object();
            info.put("info", BackendResponses.fileInfo(definition.logging(), response));
            return new TestCallResult(sent, new TestCallResult.ResponseView(status, headers, info, millis(started)),
                    new TestCallResult.ClientView(status, info), outcome.name(), TestInterpretation.of(outcome, status),
                    List.of());
        }
        BackendResponses.Body body;
        try {
            body = BackendResponses.read(response, maxResponseBytes,
                    started + TimeUnit.MILLISECONDS.toNanos(definition.timeoutMs()));
        } catch (TimeoutException e) {
            return failed(sent, Outcome.TIMEOUT, profile, requestId, started);
        } catch (IOException e) {
            return failed(sent, Outcome.TECHNICAL_ERROR, profile, requestId, started);
        }
        JsonNode parsed = body.tooLarge() ? Json.string("Yanıt çok büyük")
                : BackendResponses.parse(body.bytes(), response.headers().firstValue("Content-Type").orElse(null),
                definition.responseType());
        JsonNode shown = Json.isMissing(parsed) ? null : parsed;
        TestCallResult.ClientView client;
        if (profile != null) {
            ClientResponse wrapped = profile.render(new ResponseProfile.BackendResult(status, parsed, outcome, requestId));
            client = new TestCallResult.ClientView(wrapped.status(), wrapped.body());
        } else {
            client = new TestCallResult.ClientView(status, shown);
        }
        return new TestCallResult(sent, new TestCallResult.ResponseView(status, headers, shown, millis(started)), client,
                outcome.name(), TestInterpretation.of(outcome, status), List.of());
    }

    private static TestCallResult failed(TestCallResult.RequestView sent, Outcome outcome, ResponseProfile profile,
                                         String requestId, long started) {
        TestCallResult.ClientView client;
        if (profile != null) {
            ClientResponse wrapped = profile.render(new ResponseProfile.BackendResult(null, null, outcome, requestId));
            client = new TestCallResult.ClientView(wrapped.status(), wrapped.body());
        } else {
            int status = outcome == Outcome.TIMEOUT ? 504 : 502;
            ObjectNode error = Json.object();
            error.put("result", false);
            error.put("status", status);
            error.put("outcome", outcome.name());
            error.put("requestId", requestId);
            client = new TestCallResult.ClientView(status, error);
        }
        return new TestCallResult(sent, new TestCallResult.ResponseView(null, Map.of(), null, millis(started)), client,
                outcome.name(), TestInterpretation.of(outcome, null), List.of());
    }

    private static TestCallResult invalid(List<FieldError> errors) {
        return new TestCallResult(null, null, null, Outcome.INVALID_REQUEST.name(),
                TestInterpretation.of(Outcome.INVALID_REQUEST, null), errors);
    }

    private static Map<String, List<String>> headers(HttpResponse<?> response) {
        Map<String, List<String>> headers = new TreeMap<>();
        response.headers().map().forEach((name, values) -> {
            if (!name.startsWith(":")) {
                headers.put(canonical(name), values);
            }
        });
        return headers;
    }

    private static String canonical(String name) {
        StringBuilder result = new StringBuilder();
        boolean upper = true;
        for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
            result.append(upper ? Character.toUpperCase(c) : c);
            upper = c == '-';
        }
        return result.toString();
    }

    private static long millis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static ResponseEntity<ObjectNode> problem(HttpStatus status, String code, String detail,
                                                      List<FieldError> errors) {
        ObjectNode body = Json.object();
        body.put("type", "about:blank");
        body.put("title", status == HttpStatus.FORBIDDEN ? "Yetki yok" : "Doğrulama hatası");
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("code", code);
        var list = body.putArray("errors");
        for (FieldError error : errors) {
            ObjectNode item = list.addObject();
            item.put("field", error.field());
            item.put("message", error.message());
        }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }
}
