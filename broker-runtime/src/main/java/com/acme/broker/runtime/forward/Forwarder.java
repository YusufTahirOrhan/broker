package com.acme.broker.runtime.forward;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

import org.springframework.stereotype.Component;

import com.acme.broker.core.request.BackendRequest;
import com.acme.broker.core.route.HttpMethodName;
import com.acme.broker.runtime.config.BrokerProperties;

/**
 * Sends the backend request with the route's timeout. Only GET is retried, and only on 502, 503, 504 or a
 * connection failure; a timeout is not retried, it already used the route's whole budget. Redirects are not
 * followed and no proxy is used: backends are inside the environment.
 */
@Component
public class Forwarder {

    private static final Set<Integer> RETRYABLE = Set.of(502, 503, 504);
    /** Headers the JDK client sets itself and refuses from callers. */
    private static final Set<String> CLIENT_MANAGED = Set.of("connection", "content-length", "expect", "host", "upgrade");

    private final HttpClient client;

    public Forwarder(BrokerProperties properties) {
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.forward().connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(HttpClient.Builder.NO_PROXY)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    /** The outcome of the call: a response whose body is still to be read, a timeout or a failure. */
    public sealed interface Result {
        long startedNanos();
    }

    /** Status and headers arrived; the caller reads or streams {@code response.body()} and must close it. */
    public record Responded(HttpResponse<InputStream> response, long startedNanos, int attempts) implements Result {
    }

    public record TimedOut(long startedNanos) implements Result {
    }

    public record Failed(IOException error, long startedNanos) implements Result {
    }

    public Result send(BackendRequest request, int timeoutMs, int maxAttempts, Map<String, String> extraHeaders) {
        long started = System.nanoTime();
        int attempts = request.method() == HttpMethodName.GET ? Math.max(1, maxAttempts) : 1;
        HttpRequest httpRequest = toHttpRequest(request, timeoutMs, extraHeaders);
        for (int attempt = 1; ; attempt++) {
            try {
                HttpResponse<InputStream> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
                if (attempt < attempts && RETRYABLE.contains(response.statusCode())) {
                    discard(response);
                    continue;
                }
                return new Responded(response, started, attempt);
            } catch (HttpTimeoutException e) {
                return new TimedOut(started);
            } catch (IOException e) {
                if (attempt < attempts) {
                    continue;
                }
                return new Failed(e, started);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Failed(new IOException("Çağrı kesildi", e), started);
            }
        }
    }

    private static HttpRequest toHttpRequest(BackendRequest request, int timeoutMs, Map<String, String> extraHeaders) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
                .timeout(Duration.ofMillis(timeoutMs))
                .method(request.method().name(), request.hasBody()
                        ? HttpRequest.BodyPublishers.ofByteArray(request.body())
                        : HttpRequest.BodyPublishers.noBody());
        request.headers().forEach((name, values) -> header(builder, name, values));
        extraHeaders.forEach((name, value) -> header(builder, name, List.of(value)));
        return builder.build();
    }

    private static void header(HttpRequest.Builder builder, String name, List<String> values) {
        if (CLIENT_MANAGED.contains(name.toLowerCase(Locale.ROOT))) {
            return;
        }
        for (String value : values) {
            builder.header(name, value);
        }
    }

    private static void discard(HttpResponse<InputStream> response) {
        try (InputStream body = response.body()) {
            body.transferTo(java.io.OutputStream.nullOutputStream());
        } catch (IOException ignored) {
            // the retry does not need this body
        }
    }
}
