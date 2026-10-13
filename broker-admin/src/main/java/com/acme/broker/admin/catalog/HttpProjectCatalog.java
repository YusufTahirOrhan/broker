package com.acme.broker.admin.catalog;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;

import com.acme.broker.admin.web.ApiException;
import com.acme.broker.core.json.Json;

import tools.jackson.databind.JsonNode;

/**
 * Assumed API, to be confirmed with the project service: {@code GET /projects?query=} → {@code [{"id", "name"}]} and
 * {@code GET /projects/{id}} → {@code {"id", "name"}} or 404.
 */
public class HttpProjectCatalog implements ProjectCatalog {

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String url;

    public HttpProjectCatalog(String url) {
        this.url = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @Override
    public List<CatalogProject> search(String query) {
        String path = "/projects" + (query == null || query.isBlank() ? ""
                : "?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
        HttpResponse<byte[]> response = get(path);
        if (response.statusCode() != 200) {
            throw upstream("Proje servisi " + response.statusCode() + " döndü.");
        }
        List<CatalogProject> projects = new ArrayList<>();
        JsonNode list = Json.tryParse(response.body());
        if (list != null && list.isArray()) {
            for (JsonNode item : list) {
                projects.add(new CatalogProject(Json.text(item.get("id")), Json.text(item.get("name"))));
            }
        }
        return projects;
    }

    @Override
    public Optional<CatalogProject> find(String id) {
        HttpResponse<byte[]> response = get("/projects/" + URLEncoder.encode(id, StandardCharsets.UTF_8));
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw upstream("Proje servisi " + response.statusCode() + " döndü.");
        }
        JsonNode item = Json.tryParse(response.body());
        return item == null ? Optional.empty()
                : Optional.of(new CatalogProject(Json.text(item.get("id")), Json.text(item.get("name"))));
    }

    private HttpResponse<byte[]> get(String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url + path)).timeout(Duration.ofSeconds(10)).GET()
                .header("Accept", "application/json").build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw upstream("Proje servisine ulaşılamadı.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw upstream("Proje servisi çağrısı kesildi.");
        }
    }

    private static ApiException upstream(String detail) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "UPSTREAM_ERROR", detail);
    }
}
