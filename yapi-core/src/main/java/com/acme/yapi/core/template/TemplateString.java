package com.acme.yapi.core.template;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.acme.yapi.core.json.Json;

import tools.jackson.databind.JsonNode;

/** A string that may contain placeholders: {@code "${request.id}"}, {@code "Bearer-${user.id}"} or plain text. */
public final class TemplateString {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]*)}");

    private final String source;
    private final List<Object> parts;

    private TemplateString(String source, List<Object> parts) {
        this.source = source;
        this.parts = parts;
    }

    public static TemplateString parse(String source) {
        List<Object> parts = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(source);
        int last = 0;
        while (matcher.find()) {
            if (matcher.start() > last) {
                parts.add(source.substring(last, matcher.start()));
            }
            parts.add(Placeholder.parse(matcher.group(1)));
            last = matcher.end();
        }
        if (last < source.length()) {
            parts.add(source.substring(last));
        }
        return new TemplateString(source, List.copyOf(parts));
    }

    public String source() {
        return source;
    }

    public List<Placeholder> placeholders() {
        List<Placeholder> result = new ArrayList<>();
        for (Object part : parts) {
            if (part instanceof Placeholder placeholder) {
                result.add(placeholder);
            }
        }
        return result;
    }

    public boolean isPlain() {
        return placeholders().isEmpty();
    }

    /** Exactly one placeholder and no surrounding text: the value keeps its JSON type. */
    public boolean isSinglePlaceholder() {
        return parts.size() == 1 && parts.getFirst() instanceof Placeholder;
    }

    /** An unbalanced {@code ${} that the pattern could not read as a placeholder. */
    public boolean hasUnclosedPlaceholder() {
        for (Object part : parts) {
            if (part instanceof String text && text.contains("${")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Renders the string. A single placeholder returns the resolved value with its type. Mixed text returns a
     * string; when any of its placeholders has no value the whole result is missing, so the caller leaves the
     * field out instead of sending a half-filled value.
     */
    public JsonNode render(Resolver resolver, boolean keepNulls) {
        return render(resolver, keepNulls, false);
    }

    /**
     * As {@link #render(Resolver, boolean)}; with {@code absentAsEmpty} a placeholder without a value inside text
     * becomes empty text instead of leaving the whole value out (response profiles).
     */
    public JsonNode render(Resolver resolver, boolean keepNulls, boolean absentAsEmpty) {
        if (isSinglePlaceholder()) {
            JsonNode value = resolver.resolve((Placeholder) parts.getFirst());
            if (Json.isMissing(value)) {
                return Json.missing();
            }
            if (value.isNull()) {
                return keepNulls ? Json.nullNode() : Json.missing();
            }
            return value;
        }
        StringBuilder text = new StringBuilder();
        for (Object part : parts) {
            if (part instanceof Placeholder placeholder) {
                JsonNode value = resolver.resolve(placeholder);
                if (Json.isAbsent(value)) {
                    if (!absentAsEmpty) {
                        return Json.missing();
                    }
                    continue;
                }
                text.append(Json.text(value));
            } else {
                text.append((String) part);
            }
        }
        return Json.string(text.toString());
    }

    @Override
    public String toString() {
        return source;
    }
}
