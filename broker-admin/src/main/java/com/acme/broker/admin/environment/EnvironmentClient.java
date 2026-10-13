package com.acme.broker.admin.environment;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLContext;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.acme.broker.admin.config.AdminProperties;
import com.acme.broker.admin.web.ApiException;
import com.acme.broker.core.json.Json;

import tools.jackson.databind.JsonNode;

/**
 * The central admin's only way into an environment: its Admin API, over mTLS with the client certificate of the
 * {@code broker.admin.environments.ssl-bundle} bundle. Redirects are not followed.
 */
@Component
public class EnvironmentClient {

    private final HttpClient client;
    private final AdminProperties.Environments settings;

    public EnvironmentClient(AdminProperties properties, ObjectProvider<SslBundles> bundles) {
        this.settings = properties.environments();
        HttpClient.Builder builder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER);
        if (settings.sslBundle() != null && !settings.sslBundle().isBlank()) {
            SSLContext context = bundles.getObject().getBundle(settings.sslBundle()).createSslContext();
            builder.sslContext(context);
        }
        this.client = builder.build();
    }

    /** The Admin API's answer; {@code body} is null when it was empty. */
    public record Response(int status, JsonNode body) {

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public String code() {
            return body == null ? null : Json.stringField(body, "code");
        }

        public String detail() {
            return body == null ? null : Json.stringField(body, "detail");
        }
    }

    /** The request did not reach the Admin API (connection refused, DNS, TLS); nothing changed there. */
    public static final class UnreachableException extends Exception {
        public UnreachableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The request was sent but no answer came; the Admin API may or may not have applied it. */
    public static final class NoAnswerException extends Exception {
        public NoAnswerException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public Response send(Environment environment, String method, String path, Object body)
            throws UnreachableException, NoAnswerException {
        return send(environment, method, path, body, Map.of());
    }

    public Response send(Environment environment, String method, String path, Object body, Map<String, String> headers)
            throws UnreachableException, NoAnswerException {
        String base = environment.adminApiUrl().endsWith("/")
                ? environment.adminApiUrl().substring(0, environment.adminApiUrl().length() - 1)
                : environment.adminApiUrl();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(settings.timeout())
                .header("Accept", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(Json.writeBytes(body)));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        headers.forEach(request::header);
        try {
            HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Response(response.statusCode(), Json.tryParse(response.body()));
        } catch (HttpConnectTimeoutException e) {
            throw new UnreachableException(environment.code() + " ortamının Admin API'sine bağlanılamadı", e);
        } catch (HttpTimeoutException e) {
            throw new NoAnswerException(environment.code() + " ortamının Admin API'si zamanında yanıt vermedi", e);
        } catch (IOException e) {
            if (e instanceof java.net.ConnectException || e.getCause() instanceof java.net.ConnectException
                    || e instanceof javax.net.ssl.SSLException) {
                throw new UnreachableException(environment.code() + " ortamının Admin API'sine ulaşılamadı", e);
            }
            throw new NoAnswerException(environment.code() + " ortamının Admin API'si yanıtı tamamlanmadı", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NoAnswerException("Çağrı kesildi", e);
        }
    }

    /**
     * A read or a call whose failure simply fails the user's request: unreachable environments give 502
     * ENV_UNREACHABLE; an error the Admin API returned keeps its status, code and detail.
     */
    public JsonNode call(Environment environment, String method, String path, Object body) {
        Response response;
        try {
            response = send(environment, method, path, body);
        } catch (UnreachableException | NoAnswerException e) {
            throw unreachable(environment, e);
        }
        if (!response.ok()) {
            throw error(environment, response);
        }
        return response.body();
    }

    public static ApiException unreachable(Environment environment, Exception e) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "ENV_UNREACHABLE",
                environment.code() + " ortamının Admin API'sine ulaşılamadı.", List.of(),
                Map.of("environment", environment.code(), "reason", String.valueOf(e.getMessage())));
    }

    public static ApiException error(Environment environment, Response response) {
        HttpStatus status = HttpStatus.resolve(response.status());
        if (status == null || response.code() == null) {
            return new ApiException(HttpStatus.BAD_GATEWAY, "ENV_UNREACHABLE",
                    environment.code() + " ortamının Admin API'si beklenmeyen bir yanıt verdi (" + response.status() + ").");
        }
        String code = switch (response.code()) {
            case "BROKER_UNREACHABLE", "CLIENT_NOT_ALLOWED" -> "ENV_UNREACHABLE";
            case "PROJECT_NOT_FOUND", "ROUTE_NOT_FOUND" -> "NOT_FOUND";
            default -> response.code();
        };
        HttpStatus mapped = code.equals("ENV_UNREACHABLE") ? HttpStatus.BAD_GATEWAY : status;
        String detail = response.detail() == null ? "Ortam hata döndü." : response.detail();
        return new ApiException(mapped, code, environment.code() + ": " + detail, List.of(),
                Map.of("environment", environment.code()));
    }
}
