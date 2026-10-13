package com.acme.broker.core.route;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.acme.broker.core.template.TemplateString;

/** How the frontend body maps onto the backend request. Map iteration order is the definition order. */
public record RequestSpec(List<FieldSpec> fields,
                          Map<String, TemplateString> pathVariables,
                          Map<String, TemplateString> queryParams,
                          Map<String, TemplateString> headers,
                          BodySpec body) {

    public Optional<FieldSpec> field(String name) {
        return fields.stream().filter(field -> field.name().equals(name)).findFirst();
    }
}
