package com.acme.yapi.core.testing;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.spec.OpenApiSpec;

import tools.jackson.databind.JsonNode;

/** Examples from the design document, shared by the tests of every module through the test-jar. */
public final class CoreFixtures {

    /** The {@code add_order_note} definition: {@code POST /orders/{id}/notes}. */
    public static final String ADD_ORDER_NOTE = """
            {
              "request": {
                "fields": [
                  { "name": "siparisNo", "type": "integer", "required": true },
                  { "name": "notMetni", "type": "string", "required": true },
                  { "name": "bildir", "type": "boolean", "default": false }
                ],
                "pathVariables": { "id": "${request.siparisNo}" },
                "queryParams": { "notify": "${request.bildir}" },
                "headers": { "X-Channel": "WEB", "Accept-Language": "${header.Accept-Language}" },
                "body": {
                  "mode": "TEMPLATE",
                  "contentType": "application/json",
                  "template": { "text": "${request.notMetni}", "authorId": "${user.id}", "source": "WEB" }
                }
              },
              "response": { "type": "JSON" },
              "timeoutMs": 5000,
              "retry": { "maxAttempts": 1 },
              "logging": { "logRequestBody": true, "logResponseBody": false,
                           "maskFields": ["password", "cardNo", "tckn"], "maxBodyBytes": 65536 }
            }
            """;
    /**
     * add_order_note with a constant text of at least {@code bytes} UTF-8 bytes in its body template: larger than any
     * VARCHAR2 bind, and with Turkish letters so that bytes and characters differ.
     */
    public static String largeAddOrderNote(int bytes) {
        String unit = "çağrı merkezi notu şablonu; ";
        String text = unit.repeat(bytes / unit.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1);
        return ADD_ORDER_NOTE.replace("\"source\": \"WEB\" }", "\"source\": \"WEB\", \"terms\": \"" + text + "\" }");
    }


    /** The {@code log-project} profile, the counterpart of {@code LogProjectResponse}. */
    public static final String LOG_PROJECT_PROFILE = """
            {
              "success": {
                "httpStatus": 200,
                "body": { "BACKEND_STATUS": "${backend.status}", "status": 200, "data": "${backend.body}" }
              },
              "error": {
                "httpStatus": 400,
                "body": { "status": 400, "reason": "LogProject Hata!" }
              }
            }
            """;

    private CoreFixtures() {
    }

    /** {@code openapi-sample.json}: a springdoc document with orders, notes, files and an excel report. */
    public static JsonNode sampleSpec() {
        try (InputStream in = CoreFixtures.class.getResourceAsStream("/openapi-sample.json")) {
            return Json.parse(in.readAllBytes());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Map<String, OpenApiSpec.Operation> operationsByKey(JsonNode spec) {
        return OpenApiSpec.operations(spec).stream()
                .collect(Collectors.toMap(operation -> operation.key(), Function.identity()));
    }
}
