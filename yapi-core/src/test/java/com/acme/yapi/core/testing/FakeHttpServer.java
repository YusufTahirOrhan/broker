package com.acme.yapi.core.testing;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A backend service for tests on the JDK's HTTP server. Handlers are registered per {@code METHOD path}; every
 * request is recorded, and unknown paths answer 404 like Spring MVC does.
 */
public final class FakeHttpServer implements AutoCloseable {

    /** A request as the service saw it; header names are lower-case. */
    public record Recorded(String method, String path, String query, Map<String, List<String>> headers, byte[] body) {

        public String header(String name) {
            List<String> values = headers.get(name.toLowerCase(java.util.Locale.ROOT));
            return values == null || values.isEmpty() ? null : values.getFirst();
        }

        public String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    @FunctionalInterface
    public interface Handler {
        void handle(HttpExchange exchange, Recorded request) throws IOException;
    }

    private final HttpServer server;
    private final Map<String, Handler> handlers = new ConcurrentHashMap<>();
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private volatile Handler otherwise;

    private FakeHttpServer(HttpServer server) {
        this.server = server;
    }

    public static FakeHttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            FakeHttpServer fake = new FakeHttpServer(server);
            server.createContext("/", fake::dispatch);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            return fake;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code method} may be {@code *} for any method. */
    public FakeHttpServer on(String method, String path, Handler handler) {
        handlers.put(method + " " + path, handler);
        return this;
    }

    /** Handles requests no {@code METHOD path} handler matched; without it they get 404. */
    public FakeHttpServer otherwise(Handler handler) {
        this.otherwise = handler;
        return this;
    }

    public FakeHttpServer json(String method, String path, int status, String json) {
        return on(method, path, (exchange, request) -> respond(exchange, status, Map.of("Content-Type", "application/json"),
                json.getBytes(StandardCharsets.UTF_8)));
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public List<Recorded> requests() {
        return List.copyOf(requests);
    }

    public List<Recorded> requests(String path) {
        return requests.stream().filter(request -> request.path().equals(path)).toList();
    }

    public void reset() {
        handlers.clear();
        requests.clear();
        otherwise = null;
    }

    public static void respond(HttpExchange exchange, int status, Map<String, String> headers, byte[] body)
            throws IOException {
        headers.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        boolean empty = body == null || body.length == 0;
        exchange.sendResponseHeaders(status, empty ? -1 : body.length);
        if (!empty) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
        exchange.close();
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        byte[] body;
        try (InputStream in = exchange.getRequestBody()) {
            body = in.readAllBytes();
        }
        Map<String, List<String>> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) ->
                headers.put(name.toLowerCase(java.util.Locale.ROOT), new ArrayList<>(values)));
        String path = exchange.getRequestURI().getRawPath();
        Recorded recorded = new Recorded(exchange.getRequestMethod(), path, exchange.getRequestURI().getRawQuery(),
                headers, body);
        requests.add(recorded);
        Handler handler = handlers.get(exchange.getRequestMethod() + " " + path);
        if (handler == null) {
            handler = handlers.get("* " + path);
        }
        if (handler == null) {
            handler = otherwise;
        }
        try {
            if (handler == null) {
                respond(exchange, 404, Map.of(), null);
            } else {
                handler.handle(exchange, recorded);
            }
        } catch (IOException | RuntimeException e) {
            exchange.close();
            throw e;
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
