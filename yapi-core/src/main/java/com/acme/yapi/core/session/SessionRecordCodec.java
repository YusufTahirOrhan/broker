package com.acme.yapi.core.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.acme.yapi.core.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Redis key and value of a {@link SessionRecord}, as the legacy broker's {@code RedisTemplate} has them: the key a
 * plain string ({@code StringRedisSerializer}), the value a UTF-8 JSON object without type information
 * ({@code JacksonJsonRedisSerializer<>(Object.class)}), so both brokers read and write the same records. Empty values
 * are written as JSON {@code null}. Key pattern and field names come from settings; {@link #DEFAULT} is the legacy
 * format.
 *
 * @param keyPattern     Redis key with a {@code {uid}} placeholder
 * @param uidField       field of the user ID
 * @param tokenField     field of {@code X-Token}
 * @param clientIpField  field of {@code X-Clientip}
 * @param userAgentField field of {@code X-User-Agent}
 * @param permissionsField field of the permission list
 */
public record SessionRecordCodec(String keyPattern, String uidField, String tokenField, String clientIpField,
                                 String userAgentField, String permissionsField) {

    public static final String KEY_PATTERN = "user:session:{uid}";
    public static final SessionRecordCodec DEFAULT = new SessionRecordCodec(null, null, null, null, null, null);

    public SessionRecordCodec {
        keyPattern = keyPattern == null ? KEY_PATTERN : keyPattern;
        uidField = uidField == null ? "uid" : uidField;
        tokenField = tokenField == null ? "X-Token" : tokenField;
        clientIpField = clientIpField == null ? "X-Clientip" : clientIpField;
        userAgentField = userAgentField == null ? "X-User-Agent" : userAgentField;
        permissionsField = permissionsField == null ? "permissions" : permissionsField;
    }

    public String key(String uid) {
        return keyPattern.replace("{uid}", uid);
    }

    public String write(SessionRecord record) {
        ObjectNode value = Json.object();
        putText(value, uidField, record.uid());
        putText(value, tokenField, record.token());
        putText(value, clientIpField, record.clientIp());
        putText(value, userAgentField, record.userAgent());
        if (record.permissions() == null) {
            value.putNull(permissionsField);
        } else {
            ArrayNode permissions = value.putArray(permissionsField);
            record.permissions().forEach(permissions::add);
        }
        return Json.write(value);
    }

    /** Empty when the value is missing or is not a JSON object; fields that are absent or not text read as null. */
    public Optional<SessionRecord> read(String value) {
        JsonNode node = Json.tryParse(value);
        if (node == null || !node.isObject()) {
            return Optional.empty();
        }
        List<String> permissions = null;
        JsonNode list = node.get(permissionsField);
        if (list != null && list.isArray()) {
            permissions = new ArrayList<>();
            for (JsonNode permission : list) {
                String code = Json.isString(permission) ? permission.stringValue() : null;
                if (code != null) {
                    permissions.add(code);
                }
            }
        }
        return Optional.of(new SessionRecord(Json.stringField(node, uidField), Json.stringField(node, tokenField),
                Json.stringField(node, clientIpField), Json.stringField(node, userAgentField), permissions));
    }

    private static void putText(ObjectNode node, String field, String value) {
        if (value == null || value.isEmpty()) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }
}
