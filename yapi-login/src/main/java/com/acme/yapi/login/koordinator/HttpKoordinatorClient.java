package com.acme.yapi.login.koordinator;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.acme.yapi.core.json.Json;
import com.acme.yapi.login.config.KoordinatorProperties;

import tools.jackson.databind.JsonNode;

/** The Koordinatör over HTTP, with the paths and fields of {@link KoordinatorProperties}. */
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
            Optional<List<String>> permissions = activePermissions(Json.tryParse(response.body()));
            if (permissions.isEmpty()) {
                // an empty map is an answer (no permissions); any other shape is the Koordinatör's error
                log.warn("Koordinatör'ün yetki cevabı beklenen biçimde değil: uid={}", uid);
            }
            return permissions;
        } catch (IOException e) {
            log.warn("Koordinatör'e ulaşılamadı: uid={} ({})", uid, e.toString());
            return Optional.empty();
        }
    }

    /**
     * The names of every list of the map, in order and without repeats, leaving out suspended permissions; empty
     * when the body is not a map of lists of objects with a name.
     */
    private Optional<List<String>> activePermissions(JsonNode body) {
        if (body == null || !body.isObject()) {
            return Optional.empty();
        }
        Set<String> names = new LinkedHashSet<>();
        for (Map.Entry<String, JsonNode> list : Json.fields(body)) {
            if (!list.getValue().isArray()) {
                return Optional.empty();
            }
            for (JsonNode permission : list.getValue()) {
                String name = Json.stringField(permission, settings.permissionField());
                if (name == null) {
                    return Optional.empty();
                }
                if (!Json.isTrue(permission.get(settings.suspendedField()))) {
                    names.add(name);
                }
            }
        }
        return Optional.of(List.copyOf(names));
    }

    @Override
    public List<String> applications() throws IOException {
        HttpResponse<byte[]> response = get(settings.projectsPath());
        if (response.statusCode() != 200) {
            throw new IOException("Koordinatör proje listesi " + response.statusCode() + " döndü");
        }
        JsonNode list = Json.tryParse(response.body());
        if (list == null || !list.isArray()) {
            throw new IOException("Koordinatör proje listesi bir JSON dizisi değil");
        }
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode project : list) {
            String name = Json.stringField(project, settings.applicationField());
            if (name != null && !name.isBlank() && Json.isTrue(project.get(settings.yapiField()))) {
                names.add(name);
            }
        }
        return List.copyOf(names);
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
}
