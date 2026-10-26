package com.acme.yapi.core.route;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A backend path such as {@code /orders/{id}/notes}. */
public final class PathTemplate {

    public static final int MAX_LENGTH = 512;
    private static final Pattern VARIABLE = Pattern.compile("\\{([^{}]*)}");
    private static final Pattern VARIABLE_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_.-]*$");

    private final String source;
    private final List<Object> parts;

    private PathTemplate(String source, List<Object> parts) {
        this.source = source;
        this.parts = parts;
    }

    /** Problems in a path template, empty when it is valid. */
    public static List<String> problems(String source) {
        List<String> problems = new ArrayList<>();
        if (source == null || source.isEmpty()) {
            problems.add("Path şablonu boş olamaz");
            return problems;
        }
        if (!source.startsWith("/")) {
            problems.add("Path şablonu / ile başlamalı");
        }
        if (source.length() > MAX_LENGTH) {
            problems.add("Path şablonu en fazla " + MAX_LENGTH + " karakter olabilir");
        }
        if (source.contains("?") || source.contains("#")) {
            problems.add("Path şablonu ? ya da # içeremez; query parametreleri queryParams'ta tanımlanır");
        }
        if (source.contains("//")) {
            problems.add("Path şablonunda boş segment olamaz");
        }
        for (int i = 0; i < source.length(); i++) {
            if (Character.isWhitespace(source.charAt(i))) {
                problems.add("Path şablonu boşluk içeremez");
                break;
            }
        }
        String withoutVariables = VARIABLE.matcher(source).replaceAll("");
        if (withoutVariables.contains("{") || withoutVariables.contains("}")) {
            problems.add("Path şablonundaki süslü parantezler dengesiz");
        }
        Matcher matcher = VARIABLE.matcher(source);
        while (matcher.find()) {
            if (!VARIABLE_NAME.matcher(matcher.group(1)).matches()) {
                problems.add("Geçersiz path değişkeni: {" + matcher.group(1) + "}");
            }
        }
        return problems;
    }

    public static PathTemplate parse(String source) {
        List<String> problems = problems(source);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", problems));
        }
        List<Object> parts = new ArrayList<>();
        Matcher matcher = VARIABLE.matcher(source);
        int last = 0;
        while (matcher.find()) {
            if (matcher.start() > last) {
                parts.add(source.substring(last, matcher.start()));
            }
            parts.add(new Variable(matcher.group(1)));
            last = matcher.end();
        }
        if (last < source.length()) {
            parts.add(source.substring(last));
        }
        return new PathTemplate(source, List.copyOf(parts));
    }

    public String source() {
        return source;
    }

    public Set<String> variables() {
        Set<String> names = new LinkedHashSet<>();
        for (Object part : parts) {
            if (part instanceof Variable variable) {
                names.add(variable.name());
            }
        }
        return names;
    }

    /** Fills the variables; each value is percent-encoded as a single path segment. */
    public String expand(Map<String, String> values) {
        StringBuilder path = new StringBuilder();
        for (Object part : parts) {
            if (part instanceof Variable variable) {
                String value = values.get(variable.name());
                if (value == null) {
                    throw new IllegalArgumentException("Path değişkeninin değeri yok: {" + variable.name() + "}");
                }
                path.append(encodeSegment(value));
            } else {
                path.append((String) part);
            }
        }
        return path.toString();
    }

    /** RFC 3986 path segment encoding: everything except unreserved characters and sub-delims, ':' and '@'. */
    public static String encodeSegment(String value) {
        StringBuilder encoded = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (isPathChar(c)) {
                encoded.append((char) c);
            } else {
                encoded.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return encoded.toString();
    }

    private static boolean isPathChar(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || "-._~!$&'()*+,;=:@".indexOf(c) >= 0;
    }

    @Override
    public String toString() {
        return source;
    }

    private record Variable(String name) {
    }
}
