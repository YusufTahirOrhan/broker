package com.acme.yapi.admin.web;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.validation.FieldError;

import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** RFC 9457 problem details with {@code code} and {@code errors}, as the frontend guide specifies. */
public final class Problems {

    private Problems() {
    }

    public static ObjectNode body(HttpStatus status, String code, String detail, List<FieldError> errors,
                                  Map<String, Object> properties) {
        ObjectNode body = Json.object();
        body.put("type", "about:blank");
        body.put("title", title(status, code));
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("code", code);
        ArrayNode list = body.putArray("errors");
        for (FieldError error : errors) {
            ObjectNode item = list.addObject();
            item.put("field", error.field());
            item.put("message", error.message());
        }
        properties.forEach((name, value) -> body.set(name, Json.toTree(value)));
        return body;
    }

    public static ResponseEntity<ObjectNode> of(HttpStatus status, String code, String detail) {
        return of(status, code, detail, List.of(), Map.of());
    }

    public static ResponseEntity<ObjectNode> of(HttpStatus status, String code, String detail, List<FieldError> errors,
                                                Map<String, Object> properties) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body(status, code, detail, errors, properties));
    }

    public static ResponseEntity<ObjectNode> of(ApiException e) {
        return of(e.status(), e.code(), e.getMessage(), e.errors(), e.properties());
    }

    /** For filters that answer before Spring MVC: authentication and authorization failures. */
    public static void write(HttpServletResponse response, HttpStatus status, String code, String detail)
            throws IOException {
        write(response, status, code, detail, Map.of());
    }

    public static void write(HttpServletResponse response, HttpStatus status, String code, String detail,
                             Map<String, Object> properties) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getOutputStream().write(Json.writeBytes(body(status, code, detail, List.of(), properties)));
    }

    private static String title(HttpStatus status, String code) {
        return switch (code) {
            case "VALIDATION_FAILED" -> "Doğrulama hatası";
            case "PRECHECK_FAILED" -> "Ön kontrol başarısız";
            case "VERSION_CONFLICT" -> "Kayıt arada değişti";
            case "FOUR_EYES" -> "Dört göz kuralı";
            case "ENV_UNREACHABLE" -> "Ortama ulaşılamadı";
            case "UPSTREAM_ERROR" -> "Dış servis hatası";
            default -> switch (status) {
                case UNAUTHORIZED -> "Kimlik doğrulanamadı";
                case FORBIDDEN -> "Yetki yok";
                case NOT_FOUND -> "Bulunamadı";
                case CONFLICT -> "Çakışma";
                default -> status.is5xxServerError() ? "Sunucu hatası" : "Geçersiz istek";
            };
        };
    }
}
