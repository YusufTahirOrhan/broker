package com.acme.broker.envadmin.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.acme.broker.core.json.Json;
import com.acme.broker.core.validation.FieldError;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** RFC 9457 problem details, the same shape the central admin uses. */
public final class Problems {

    private Problems() {
    }

    public static ResponseEntity<ObjectNode> of(HttpStatus status, String code, String detail, List<FieldError> errors,
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
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    public static ResponseEntity<ObjectNode> of(ApiException e) {
        return of(e.status(), e.code(), e.getMessage(), e.errors(), e.properties());
    }

    private static String title(HttpStatus status, String code) {
        if ("VALIDATION_FAILED".equals(code)) {
            return "Doğrulama hatası";
        }
        return switch (status) {
            case BAD_REQUEST -> "Geçersiz istek";
            case UNAUTHORIZED -> "Kimlik doğrulanamadı";
            case FORBIDDEN -> "Yetki yok";
            case NOT_FOUND -> "Bulunamadı";
            case METHOD_NOT_ALLOWED -> "Method desteklenmiyor";
            case CONFLICT -> "Çakışma";
            case BAD_GATEWAY -> "Dış servis hatası";
            default -> status.is5xxServerError() ? "Sunucu hatası" : "Hata";
        };
    }
}
