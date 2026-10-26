package com.acme.yapi.envadmin.upstream;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.acme.yapi.core.route.HttpMethodName;
import com.acme.yapi.core.route.PathTemplate;
import com.acme.yapi.envadmin.config.EnvAdminProperties;

/**
 * Calls the environment's services for the spec and the OPTIONS probe. Redirects are never followed: a redirect
 * could lead outside the registered base URL.
 */
@Component
public class UpstreamClient {

    /** Sample value for path variables the caller did not fill. */
    static final String DEFAULT_PATH_VALUE = "1";

    private final HttpClient client;
    private final EnvAdminProperties.Upstream settings;

    public UpstreamClient(EnvAdminProperties properties) {
        this.settings = properties.upstream();
        this.client = HttpClient.newBuilder()
                .connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** The service's answer to the spec request. */
    public record Fetched(int status, byte[] body, boolean tooLarge) {
    }

    public static final class UnreachableException extends Exception {
        public UnreachableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public Fetched fetch(URI uri) throws UnreachableException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(settings.requestTimeout())
                .header("Accept", "application/json")
                .GET();
        settings.headers().forEach(request::header);
        try {
            HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                byte[] body = in.readNBytes(settings.maxSpecBytes() + 1);
                boolean tooLarge = body.length > settings.maxSpecBytes();
                return new Fetched(response.statusCode(), tooLarge ? new byte[0] : body, tooLarge);
            }
        } catch (IOException e) {
            throw new UnreachableException(describe(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UnreachableException("Çağrı kesildi", e);
        }
    }

    /**
     * Spring MVC answers OPTIONS for every mapped path with 200 and an {@code Allow} header, without running the
     * controller; an unknown path gives 404. 401 and 403 mean an identity problem, not a missing endpoint.
     */
    public ProbeResult probe(String baseUrl, HttpMethodName method, String pathTemplate,
                             Map<String, String> pathVariables) {
        PathTemplate template = PathTemplate.parse(pathTemplate);
        Map<String, String> values = new LinkedHashMap<>();
        for (String variable : template.variables()) {
            String value = pathVariables == null ? null : pathVariables.get(variable);
            values.put(variable, value == null || value.isEmpty() ? DEFAULT_PATH_VALUE : value);
        }
        URI uri = UpstreamPaths.resolve(baseUrl, template.expand(values));
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(settings.requestTimeout())
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody());
        settings.headers().forEach(request::header);
        try {
            HttpResponse<Void> response = client.send(request.build(), HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            List<String> allow = allow(response);
            if (status == 404) {
                return new ProbeResult(ProbeResult.PATH_NOT_FOUND, status, allow, uri.toString(), "Path serviste yok");
            }
            if ((status >= 200 && status < 300) || status == 405) {
                if (allow.isEmpty()) {
                    return new ProbeResult(ProbeResult.INCONCLUSIVE, status, allow, uri.toString(),
                            "Yanıtta Allow header'ı yok; arada OPTIONS'ı kendisi cevaplayan bir proxy olabilir");
                }
                return allow.contains(method.name())
                        ? new ProbeResult(ProbeResult.OK, status, allow, uri.toString(), "Path ve method doğru")
                        : new ProbeResult(ProbeResult.WRONG_METHOD, status, allow, uri.toString(),
                        "Path var ama " + method + " kabul edilmiyor");
            }
            String message = status == 401 || status == 403
                    ? "Servisin güvenliği yoklamayı reddetti; kimlik sorunu, endpoint'in yokluğu değil"
                    : status >= 300 && status < 400
                    ? "Servis yönlendirme döndü; yönlendirme izlenmez"
                    : "Servis beklenmeyen bir status döndü";
            return new ProbeResult(ProbeResult.INCONCLUSIVE, status, allow, uri.toString(), message);
        } catch (IOException e) {
            return new ProbeResult(ProbeResult.INCONCLUSIVE, null, List.of(), uri.toString(), describe(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProbeResult(ProbeResult.INCONCLUSIVE, null, List.of(), uri.toString(), "Yoklama kesildi");
        }
    }

    /** Forwards a test call to the broker's internal endpoint and returns its answer as is. */
    public Fetched post(URI uri, byte[] json, Map<String, String> headers, java.time.Duration timeout)
            throws UnreachableException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json));
        headers.forEach(request::header);
        try {
            HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Fetched(response.statusCode(), response.body(), false);
        } catch (IOException e) {
            throw new UnreachableException(describe(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UnreachableException("Çağrı kesildi", e);
        }
    }

    private static List<String> allow(HttpResponse<?> response) {
        Set<String> methods = new LinkedHashSet<>();
        for (String header : response.headers().allValues("Allow")) {
            for (String method : header.split(",")) {
                if (!method.isBlank()) {
                    methods.add(method.trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        return List.copyOf(methods);
    }

    private static String describe(IOException e) {
        if (e instanceof HttpTimeoutException) {
            return "Servis zamanında yanıt vermedi";
        }
        return "Servise ulaşılamadı: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }
}
