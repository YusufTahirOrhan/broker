package com.acme.yapi.runtime.web;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.route.LoggingSpec;
import com.acme.yapi.core.route.ResponseType;

import tools.jackson.databind.JsonNode;

/** Reading and interpreting what a backend returned; shared by real traffic and test calls. */
public final class BackendResponses {

    private static final Pattern FILE_NAME_STAR = Pattern.compile("filename\\*\\s*=\\s*[^']*'[^']*'([^;]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILE_NAME = Pattern.compile("filename\\s*=\\s*\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE);
    private static final ExecutorService READERS = Executors.newVirtualThreadPerTaskExecutor();

    private BackendResponses() {
    }

    /** A file is streamed, never parsed, logged or wrapped: BINARY routes and any response with Content-Disposition. */
    public static boolean isFile(ResponseType type, HttpResponse<?> response) {
        return type == ResponseType.BINARY || response.headers().firstValue("Content-Disposition").isPresent();
    }

    /** {@code info} of a file response: the route's {@code binaryInfo}, else the file name. */
    public static String fileInfo(LoggingSpec logging, HttpResponse<?> response) {
        if (logging.binaryInfo() != null && !logging.binaryInfo().isBlank()) {
            return logging.binaryInfo();
        }
        return fileName(response).map(name -> "Dosya yanıtı: " + name).orElse("Dosya yanıtı");
    }

    static Optional<String> fileName(HttpResponse<?> response) {
        Optional<String> disposition = response.headers().firstValue("Content-Disposition");
        if (disposition.isEmpty()) {
            return Optional.empty();
        }
        Matcher star = FILE_NAME_STAR.matcher(disposition.get());
        if (star.find()) {
            return Optional.of(java.net.URLDecoder.decode(star.group(1).trim(), StandardCharsets.UTF_8));
        }
        Matcher plain = FILE_NAME.matcher(disposition.get());
        return plain.find() ? Optional.of(plain.group(1).trim()) : Optional.empty();
    }

    public record Body(byte[] bytes, boolean tooLarge) {
    }

    /**
     * Reads the body within what is left of the route's timeout. The JDK client's timeout covers only the wait for
     * the status line, so a backend that sends headers and then stalls would otherwise hold the request forever.
     */
    public static Body read(HttpResponse<InputStream> response, int maxBytes, long deadlineNanos)
            throws IOException, TimeoutException {
        InputStream in = response.body();
        CompletableFuture<byte[]> reading = CompletableFuture.supplyAsync(() -> {
            try {
                return in.readNBytes(maxBytes + 1);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }, READERS);
        try {
            long remaining = Math.max(deadlineNanos - System.nanoTime(), TimeUnit.MILLISECONDS.toNanos(1));
            byte[] bytes = reading.get(remaining, TimeUnit.NANOSECONDS);
            return bytes.length > maxBytes ? new Body(new byte[0], true) : new Body(bytes, false);
        } catch (TimeoutException e) {
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof java.io.UncheckedIOException unchecked) {
                throw unchecked.getCause();
            }
            throw new IOException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Okuma kesildi", e);
        } finally {
            in.close();
        }
    }

    /**
     * The body as JSON for profiles and logs: parsed JSON when it is JSON, else text. An empty body is missing, so
     * {@code ${backend.body}} renders null.
     */
    public static JsonNode parse(byte[] bytes, String contentType, ResponseType type) {
        if (bytes == null || bytes.length == 0) {
            return Json.missing();
        }
        String lower = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (type != ResponseType.TEXT || lower.contains("json")) {
            JsonNode parsed = Json.tryParse(bytes);
            if (parsed != null) {
                return parsed;
            }
        }
        return Json.string(new String(bytes, StandardCharsets.UTF_8));
    }
}
