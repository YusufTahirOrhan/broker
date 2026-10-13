package com.acme.broker.core.route;

import com.acme.broker.core.template.TemplateString;

/**
 * A multipart part. A file part's value must resolve to {@code {fileName, contentType, base64}}; any other part is
 * sent as text, or as JSON when the value is an object or array.
 */
public record PartSpec(String name, TemplateString value, boolean file) {
}
