package com.acme.broker.envadmin.upstream;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.acme.broker.core.route.PathTemplate;

/**
 * Guards against SSRF: calls go only to a project's registered base URL, and the path a caller supplies cannot
 * change the host or climb out of the base URL's path.
 */
public final class UpstreamPaths {

    private UpstreamPaths() {
    }

    /** Problems of a path template or a spec path; empty when it is safe to append to a base URL. */
    public static List<String> problems(String path) {
        List<String> problems = new ArrayList<>(PathTemplate.problems(path));
        if (path == null || path.isEmpty()) {
            return problems;
        }
        if (path.contains("\\")) {
            problems.add("Path ters bölü içeremez");
        }
        for (String segment : path.split("/")) {
            String decoded;
            try {
                decoded = URLDecoder.decode(segment, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                problems.add("Path geçersiz yüzde kodlaması içeriyor");
                break;
            }
            if (decoded.equals(".") || decoded.equals("..") || decoded.contains("/")) {
                problems.add("Path . ya da .. segmenti içeremez");
                break;
            }
        }
        return problems;
    }

    /** Problems of a base URL: absolute http(s), no credentials, query or fragment. */
    public static List<String> baseUrlProblems(String baseUrl) {
        List<String> problems = new ArrayList<>();
        if (baseUrl == null || baseUrl.isBlank()) {
            problems.add("Base URL zorunlu");
            return problems;
        }
        if (baseUrl.length() > 512) {
            problems.add("Base URL en fazla 512 karakter olabilir");
        }
        try {
            URI uri = new URI(baseUrl);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                problems.add("Base URL http ya da https ile başlamalı");
            }
            if (uri.getHost() == null) {
                problems.add("Base URL'de host yok");
            }
            if (uri.getRawUserInfo() != null) {
                problems.add("Base URL kullanıcı bilgisi içeremez");
            }
            if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
                problems.add("Base URL query ya da fragment içeremez");
            }
        } catch (URISyntaxException e) {
            problems.add("Base URL geçerli bir adres değil");
        }
        return problems;
    }

    public static URI resolve(String baseUrl, String path) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(base + path);
    }
}
