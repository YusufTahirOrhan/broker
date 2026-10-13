package com.acme.broker.core.request;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.acme.broker.core.json.Json;
import com.acme.broker.core.route.BodyMode;
import com.acme.broker.core.route.BodySpec;
import com.acme.broker.core.route.HttpMethodName;
import com.acme.broker.core.route.PartSpec;
import com.acme.broker.core.route.PathTemplate;
import com.acme.broker.core.route.RequestSpec;
import com.acme.broker.core.route.RouteDefinition;
import com.acme.broker.core.route.RouteDefinitionParser;
import com.acme.broker.core.template.Resolver;
import com.acme.broker.core.template.TemplateString;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns a validated frontend body into the backend request: path variables, query parameters, headers and body
 * come from the definition's placeholders, the host always from the environment's registered base URL. The same
 * builder serves real traffic, the admin preview and test calls, so a test exercises the real mapping.
 */
public final class BackendRequestBuilder {

    public static final String USER_HEADER = "X-uid";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final SecureRandom RANDOM = new SecureRandom();

    private BackendRequestBuilder() {
    }

    public static BackendRequest build(HttpMethodName method, PathTemplate pathTemplate, RouteDefinition definition,
                                       String baseUrl, RequestContext context) {
        RequestSpec request = definition.request();
        Resolver resolver = context.resolver();

        Map<String, String> pathValues = new LinkedHashMap<>();
        for (Map.Entry<String, TemplateString> entry : request.pathVariables().entrySet()) {
            JsonNode value = entry.getValue().render(resolver, false);
            if (Json.isAbsent(value)) {
                throw new RequestBuildException(entry.getKey(), "Path değişkeninin değeri yok: {" + entry.getKey() + "}");
            }
            pathValues.put(entry.getKey(), Json.text(value));
        }
        String path = pathTemplate.expand(pathValues);

        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, TemplateString> entry : request.queryParams().entrySet()) {
            JsonNode value = entry.getValue().render(resolver, false);
            if (Json.isAbsent(value)) {
                continue;
            }
            if (value.isArray()) {
                for (JsonNode element : value) {
                    if (!Json.isAbsent(element)) {
                        appendQuery(query, entry.getKey(), Json.text(element));
                    }
                }
            } else {
                appendQuery(query, entry.getKey(), Json.text(value));
            }
        }

        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (Map.Entry<String, TemplateString> entry : request.headers().entrySet()) {
            JsonNode value = entry.getValue().render(resolver, false);
            if (!Json.isAbsent(value)) {
                headers.put(entry.getKey(), List.of(Json.text(value)));
            }
        }
        if (context.userId() != null) {
            headers.put(USER_HEADER, List.of(context.userId()));
        }
        headers.put(REQUEST_ID_HEADER, List.of(context.requestId()));

        Body body = buildBody(request.body(), context, resolver);
        if (body.contentType() != null) {
            headers.put("Content-Type", List.of(body.contentType()));
        }

        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        URI uri = URI.create(base + path + (query.isEmpty() ? "" : "?" + query));
        return new BackendRequest(method, uri, headers, body.bytes(), body.contentType(), body.preview());
    }

    private record Body(byte[] bytes, String contentType, JsonNode preview) {
        static Body none() {
            return new Body(null, null, Json.missing());
        }
    }

    private static Body buildBody(BodySpec spec, RequestContext context, Resolver resolver) {
        BodyMode mode = spec.mode();
        return switch (mode) {
            case NONE -> Body.none();
            case PASS_THROUGH -> {
                ObjectNode copy = context.originalBody().deepCopy();
                RouteDefinitionParser.ENVELOPE_FIELDS.forEach(copy::remove);
                yield new Body(Json.writeBytes(copy), "application/json", copy);
            }
            case TEMPLATE -> templateBody(spec, resolver);
            case MULTIPART -> multipartBody(spec, resolver);
        };
    }

    private static Body templateBody(BodySpec spec, Resolver resolver) {
        JsonNode rendered = spec.template().render(resolver, false);
        if (Json.isMissing(rendered)) {
            return Body.none();
        }
        String contentType = spec.effectiveContentType();
        String lower = contentType.toLowerCase(Locale.ROOT);
        if (lower.startsWith("application/x-www-form-urlencoded") && rendered.isObject()) {
            StringBuilder form = new StringBuilder();
            for (Map.Entry<String, JsonNode> entry : Json.fields(rendered)) {
                if (!Json.isAbsent(entry.getValue())) {
                    appendQuery(form, entry.getKey(), Json.text(entry.getValue()));
                }
            }
            return new Body(form.toString().getBytes(StandardCharsets.UTF_8), contentType, rendered);
        }
        if (lower.startsWith("text/") && rendered.isString()) {
            return new Body(rendered.stringValue().getBytes(StandardCharsets.UTF_8), contentType, rendered);
        }
        return new Body(Json.writeBytes(rendered), contentType, rendered);
    }

    private static Body multipartBody(BodySpec spec, Resolver resolver) {
        String boundary = "----broker" + HexFormat.of().formatHex(randomBytes());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ArrayNode preview = Json.array();
        for (PartSpec part : spec.parts()) {
            JsonNode value = part.value().render(resolver, false);
            if (Json.isAbsent(value)) {
                continue;
            }
            ObjectNode summary = preview.addObject();
            summary.put("name", part.name());
            write(out, "--" + boundary + "\r\n");
            if (part.file()) {
                if (!value.isObject() || Json.stringField(value, "base64") == null) {
                    throw new RequestBuildException(part.name(),
                            "Dosya parçası {fileName, contentType, base64} nesnesi olmalı: " + part.name());
                }
                byte[] content;
                try {
                    content = Base64.getDecoder().decode(Json.stringField(value, "base64"));
                } catch (IllegalArgumentException e) {
                    throw new RequestBuildException(part.name(), "Dosya içeriği geçerli base64 değil: " + part.name());
                }
                String fileName = valueOr(Json.stringField(value, "fileName"), part.name());
                String contentType = valueOr(Json.stringField(value, "contentType"), "application/octet-stream");
                write(out, "Content-Disposition: form-data; name=\"" + quote(part.name()) + "\"; filename=\""
                        + quote(fileName) + "\"\r\n");
                write(out, "Content-Type: " + contentType + "\r\n\r\n");
                out.writeBytes(content);
                summary.put("fileName", fileName);
                summary.put("contentType", contentType);
                summary.put("size", content.length);
            } else {
                boolean json = value.isObject() || value.isArray();
                String contentType = json ? "application/json" : "text/plain; charset=UTF-8";
                byte[] content = (json ? value.toString() : Json.text(value)).getBytes(StandardCharsets.UTF_8);
                write(out, "Content-Disposition: form-data; name=\"" + quote(part.name()) + "\"\r\n");
                write(out, "Content-Type: " + contentType + "\r\n\r\n");
                out.writeBytes(content);
                summary.set("value", value);
            }
            write(out, "\r\n");
        }
        write(out, "--" + boundary + "--\r\n");
        return new Body(out.toByteArray(), "multipart/form-data; boundary=" + boundary, preview);
    }

    private static void appendQuery(StringBuilder query, String name, String value) {
        if (!query.isEmpty()) {
            query.append('&');
        }
        query.append(URLEncoder.encode(name, StandardCharsets.UTF_8))
                .append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    private static void write(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String quote(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "");
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static byte[] randomBytes() {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    /** Header names and values as a flat list, for logs and admin screens. */
    public static List<String> headerLines(BackendRequest request) {
        List<String> lines = new ArrayList<>();
        request.headers().forEach((name, values) -> values.forEach(value -> lines.add(name + ": " + value)));
        return lines;
    }
}
