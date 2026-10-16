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

import com.acme.broker.core.json.Json;
import com.acme.broker.login.config.KoordinatorProperties;

import tools.jackson.databind.JsonNode;

/** The Koordinatör over HTTP, with the assumed API of {@link KoordinatorProperties}. */
public class HttpKoordinatorClient implements KoordinatorClient {

    private static final Logger log = LoggerFactory.getLogger(HttpKoordinatorClient.class);

    private final KoordinatorProperties settings;
    private final HttpClient client;

    public HttpKoordinatorClient(KoordinatorProperties settings) {
        this.settings = settings;
        this.client = HttpClient.newBuilder().connectTimeout(settings.timeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public Optional<List<String>> permissions(String uid) {
        String path = settings.permissionsPath().replace("{uid}", URLEncoder.encode(uid, StandardCharsets.UTF_8));
        try {
            HttpResponse<byte[]> response = get(path);
            if (response.statusCode() != 200) {
                log.warn("Koordinatör yetkileri vermedi: uid={}, status={}", uid, response.statusCode());
                return Optional.empty();
            }
            JsonNode body = Json.tryParse(response.body());
            JsonNode list = body == null || !body.isObject() ? null : body.get(settings.permissionsField());
            if (list == null || !list.isArray()) {
                // an empty array is an answer (no permissions); a missing list is the Koordinatör's error
                log.warn("Koordinatör'ün yetki cevabında '{}' dizisi yok: uid={}", settings.permissionsField(), uid);
                return Optional.empty();
            }
            return Optional.of(strings(list));
        } catch (IOException e) {
            log.warn("Koordinatör'e ulaşılamadı: uid={} ({})", uid, e.toString());
            return Optional.empty();
        }
    }

    @Override
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
        for (JsonNode value : array) {
            if (Json.isString(value)) {
                values.add(value.stringValue());
            }
        }
        return values;
    }
}
