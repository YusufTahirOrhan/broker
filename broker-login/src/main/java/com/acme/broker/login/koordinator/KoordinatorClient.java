package com.acme.broker.login.koordinator;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.acme.broker.core.json.Json;
import com.acme.broker.login.config.KoordinatorProperties;

import tools.jackson.databind.JsonNode;

/** The Koordinatör's user permissions and projects, with the assumed API of {@link KoordinatorProperties}. */
@Component
public class KoordinatorClient {

    private static final Logger log = LoggerFactory.getLogger(KoordinatorClient.class);

    private final KoordinatorProperties settings;
    private final HttpClient client;

    public KoordinatorClient(KoordinatorProperties settings) {
        this.settings = settings;
        this.client = HttpClient.newBuilder().connectTimeout(settings.timeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** The user's permissions in the Koordinatör's order; empty when it did not answer 200. */
    public Optional<List<String>> permissions(String uid) {
        String path = settings.permissionsPath().replace("{uid}", URLEncoder.encode(uid, StandardCharsets.UTF_8));
        try {
            HttpResponse<byte[]> response = get(path);
            if (response.statusCode() != 200) {
                log.warn("Koordinatör yetkileri vermedi: uid={}, status={}", uid, response.statusCode());
                return Optional.empty();
            }
            JsonNode body = Json.tryParse(response.body());
            return Optional.of(strings(body == null ? null : body.get(settings.permissionsField())));
        } catch (IOException e) {
            log.warn("Koordinatör'e ulaşılamadı: uid={} ({})", uid, e.toString());
            return Optional.empty();
        }
    }

    /** The {@code code} of every project. */
    public List<String> projectCodes() throws IOException {
        HttpResponse<byte[]> response = get(settings.projectsPath());
        if (response.statusCode() != 200) {
            throw new IOException("Koordinatör proje listesi " + response.statusCode() + " döndü");
        }
        JsonNode list = Json.tryParse(response.body());
        if (list == null || !list.isArray()) {
            throw new IOException("Koordinatör proje listesi bir JSON dizisi değil");
        }
        List<String> codes = new ArrayList<>();
        for (JsonNode project : list) {
            String code = Json.stringField(project, settings.projectCodeField());
            if (code != null && !code.isBlank()) {
                codes.add(code);
            }
        }
        return codes;
    }

    private HttpResponse<byte[]> get(String path) throws IOException {
        HttpRequest.Builder request;
        try {
            request = HttpRequest.newBuilder(URI.create(settings.url() + path));
        } catch (IllegalArgumentException e) {
            throw new IOException("Koordinatör adresi geçersiz (koordinator.url): " + settings.url(), e);
        }
        request.timeout(settings.timeout()).header("Accept", "application/json").GET();
        if (settings.authValue() != null && !settings.authValue().isEmpty()) {
            request.header(settings.authHeader(), settings.authValue());
        }
        try {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Koordinatör çağrısı kesildi", e);
        }
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode value : array) {
                if (Json.isString(value)) {
                    values.add(value.stringValue());
                }
            }
        }
        return values;
    }
}
