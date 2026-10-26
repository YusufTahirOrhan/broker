package com.acme.yapi.core.sample;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.acme.yapi.core.json.Json;

import tools.jackson.databind.JsonNode;

/**
 * Reads what DevOps pastes: a request body copied from the browser, or the browser's "Copy as cURL" output (bash or
 * cmd flavour). Credentials are dropped while parsing ({@code X-Token}, {@code Cookie}, {@code Authorization}); the
 * raw text is never kept.
 */
public final class SampleParser {

    public static final Set<String> CREDENTIAL_HEADERS = Set.of("x-token", "cookie", "authorization");

    private SampleParser() {
    }

    /**
     * @param headers        headers that were kept, in paste order
     * @param removedHeaders credential headers that were dropped, as written in the paste
     * @param body           the JSON body, or null when there is none or it is not JSON
     */
    public record Sample(String method, String url, Map<String, String> headers, List<String> removedHeaders,
                         JsonNode body) {
    }

    public static Sample parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Yapıştırılan metin boş");
        }
        String trimmed = text.strip();
        if (!trimmed.regionMatches(true, 0, "curl", 0, 4)) {
            JsonNode body = Json.tryParse(trimmed);
            if (body == null || !body.isObject()) {
                throw new IllegalArgumentException("Metin bir JSON nesnesi ya da cURL komutu değil");
            }
            return new Sample(null, null, Map.of(), List.of(), body);
        }
        return parseCurl(trimmed);
    }

    private static Sample parseCurl(String text) {
        List<String> tokens = tokenize(isCmdFlavour(text) ? unescapeCmd(text) : text);
        String method = null;
        String url = null;
        String data = null;
        Map<String, String> headers = new LinkedHashMap<>();
        List<String> removed = new ArrayList<>();
        for (int i = 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            switch (token) {
                case "-X", "--request" -> method = next(tokens, ++i);
                case "-H", "--header" -> {
                    String header = next(tokens, ++i);
                    int colon = header.indexOf(':');
                    if (colon > 0) {
                        String name = header.substring(0, colon).trim();
                        String value = header.substring(colon + 1).trim();
                        if (CREDENTIAL_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                            removed.add(name);
                        } else {
                            headers.put(name, value);
                        }
                    }
                }
                case "-b", "--cookie" -> {
                    next(tokens, ++i);
                    removed.add("Cookie");
                }
                case "-u", "--user" -> {
                    next(tokens, ++i);
                    removed.add("Authorization");
                }
                case "-d", "--data", "--data-raw", "--data-binary", "--data-ascii", "--data-urlencode" ->
                        data = next(tokens, ++i);
                case "--url" -> url = next(tokens, ++i);
                case "-A", "--user-agent", "-e", "--referer", "-o", "--output", "-m", "--max-time",
                     "--connect-timeout", "-x", "--proxy" -> next(tokens, ++i);
                default -> {
                    if (!token.startsWith("-") && url == null) {
                        url = token;
                    }
                }
            }
        }
        if (method == null) {
            method = data == null ? "GET" : "POST";
        }
        JsonNode body = data == null ? null : Json.tryParse(data);
        return new Sample(method.toUpperCase(Locale.ROOT), url, headers, removed,
                body != null && body.isObject() ? body : null);
    }

    private static String next(List<String> tokens, int index) {
        if (index >= tokens.size()) {
            throw new IllegalArgumentException("cURL komutu eksik: " + tokens.get(index - 1) + " seçeneğinin değeri yok");
        }
        return tokens.get(index);
    }

    private static boolean isCmdFlavour(String text) {
        return text.contains("^\"") || text.matches("(?s).*\\^\\r?\\n.*");
    }

    /** Chrome's cmd flavour escapes with {@code ^}: drop line continuations, then every {@code ^x} becomes {@code x}. */
    private static String unescapeCmd(String text) {
        String joined = text.replaceAll("\\^\\r?\\n", " ");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < joined.length(); i++) {
            char c = joined.charAt(i);
            if (c == '^' && i + 1 < joined.length()) {
                result.append(joined.charAt(++i));
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    /** POSIX shell words: single quotes, double quotes with backslash escapes, {@code $'...'}, line continuations. */
    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inToken = false;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length() && (text.charAt(i + 1) == '\n' || text.charAt(i + 1) == '\r')) {
                i += text.charAt(i + 1) == '\r' && i + 2 < text.length() && text.charAt(i + 2) == '\n' ? 3 : 2;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (inToken) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    inToken = false;
                }
                i++;
                continue;
            }
            inToken = true;
            if (c == '$' && i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                i = readAnsiC(text, i + 2, current);
            } else if (c == '\'') {
                int end = text.indexOf('\'', i + 1);
                if (end < 0) {
                    throw new IllegalArgumentException("cURL komutunda kapanmamış tek tırnak");
                }
                current.append(text, i + 1, end);
                i = end + 1;
            } else if (c == '"') {
                i = readDoubleQuoted(text, i + 1, current);
            } else if (c == '\\' && i + 1 < text.length()) {
                current.append(text.charAt(i + 1));
                i += 2;
            } else {
                current.append(c);
                i++;
            }
        }
        if (inToken) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private static int readDoubleQuoted(String text, int start, StringBuilder out) {
        int i = start;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '"') {
                return i + 1;
            }
            if (c == '\\' && i + 1 < text.length() && "\"\\$`\n".indexOf(text.charAt(i + 1)) >= 0) {
                if (text.charAt(i + 1) != '\n') {
                    out.append(text.charAt(i + 1));
                }
                i += 2;
                continue;
            }
            out.append(c);
            i++;
        }
        throw new IllegalArgumentException("cURL komutunda kapanmamış çift tırnak");
    }

    private static int readAnsiC(String text, int start, StringBuilder out) {
        int i = start;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\'') {
                return i + 1;
            }
            if (c == '\\' && i + 1 < text.length()) {
                char e = text.charAt(i + 1);
                switch (e) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case '\\', '\'', '"' -> out.append(e);
                    case 'x' -> {
                        int end = Math.min(i + 4, text.length());
                        int value = Integer.parseInt(text.substring(i + 2, end), 16);
                        out.append((char) value);
                        i = end;
                        continue;
                    }
                    case 'u' -> {
                        int end = Math.min(i + 6, text.length());
                        out.append((char) Integer.parseInt(text.substring(i + 2, end), 16));
                        i = end;
                        continue;
                    }
                    default -> out.append('\\').append(e);
                }
                i += 2;
                continue;
            }
            out.append(c);
            i++;
        }
        throw new IllegalArgumentException("cURL komutunda kapanmamış $'...' ifadesi");
    }
}
