package com.acme.broker.admin.authz;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;

import com.acme.broker.admin.web.ApiException;
import com.acme.broker.core.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Assumed API of each environment's authorization service, to be confirmed: {@code GET /permissions?prefix=} →
 * {@code [{"code", "description"}]}, {@code GET /permissions/{code}} → 200 or 404, {@code POST /permissions}
 * {@code {"code", "description"}} → 201, or 409 when it exists.
 */
public class HttpAuthorizationService implements AuthorizationService {

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Map<String, String> urls;

    public HttpAuthorizationService(Map<String, String> urls) {
        this.urls = urls;
    }

    @Override
    public List<Permission> list(String environment, String prefix) {
        HttpResponse<byte[]> response = send(environment, HttpRequest.newBuilder(uri(environment,
                "/permissions?prefix=" + URLEncoder.encode(prefix, StandardCharsets.UTF_8))).GET());
        if (response.statusCode() != 200) {
            throw upstream(environment, "listeleme " + response.statusCode() + " döndü");
        }
        List<Permission> permissions = new ArrayList<>();
        JsonNode list = Json.tryParse(response.body());
        if (list != null && list.isArray()) {
            for (JsonNode item : list) {
                permissions.add(new Permission(Json.text(item.get("code")), Json.text(item.get("description"))));
            }
        }
        return permissions;
    }

    @Override
    public boolean create(String environment, String code, String description) {
        ObjectNode body = Json.object();
        body.put("code", code);
        body.put("description", description);
        HttpResponse<byte[]> response = send(environment, HttpRequest.newBuilder(uri(environment, "/permissions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(Json.writeBytes(body))));
        if (response.statusCode() == 409) {
            return false;
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw upstream(environment, "yetki açma " + response.statusCode() + " döndü");
        }
        return true;
    }

    @Override
    public Set<String> existing(String environment, Collection<String> codes) {
        Set<String> found = new LinkedHashSet<>();
        for (String code : codes) {
            HttpResponse<byte[]> response = send(environment, HttpRequest.newBuilder(uri(environment,
                    "/permissions/" + URLEncoder.encode(code, StandardCharsets.UTF_8))).GET());
            if (response.statusCode() == 200) {
                found.add(code);
            } else if (response.statusCode() != 404) {
                throw upstream(environment, "yetki sorgusu " + response.statusCode() + " döndü");
            }
        }
        return found;
    }

    private URI uri(String environment, String path) {
        String url = urls.get(environment);
        if (url == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "UPSTREAM_ERROR",
                    environment + " için yetkilendirme servisi adresi tanımlı değil.");
        }
        return URI.create((url.endsWith("/") ? url.substring(0, url.length() - 1) : url) + path);
    }

    private HttpResponse<byte[]> send(String environment, HttpRequest.Builder request) {
        try {
            return client.send(request.timeout(Duration.ofSeconds(10)).header("Accept", "application/json").build(),
                    HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw upstream(environment, "ulaşılamadı");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw upstream(environment, "çağrı kesildi");
        }
    }

    private static ApiException upstream(String environment, String what) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "UPSTREAM_ERROR",
                environment + " yetkilendirme servisi: " + what + ".");
    }
}
