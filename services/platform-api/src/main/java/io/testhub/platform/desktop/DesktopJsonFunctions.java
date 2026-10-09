package io.testhub.platform.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.regex.Pattern;

/** H2 aliases for the two MySQL JSON queries used by shared repositories. */
public final class DesktopJsonFunctions {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern FIELD_PATH = Pattern.compile("\\$\\[\\*]\\.([A-Za-z][A-Za-z0-9_]*)");

    private DesktopJsonFunctions() { }

    public static Integer length(byte[] document) throws IOException {
        if (document == null) return null;
        JsonNode value = JSON.readTree(document);
        return value.isContainerNode() ? value.size() : 1;
    }

    public static String search(byte[] document, String mode, String needle, String escape, String path) throws IOException {
        if (document == null || needle == null || path == null) return null;
        var field = FIELD_PATH.matcher(path);
        if (!"one".equalsIgnoreCase(mode) || !field.matches() || (escape != null && escape.length() > 1)) {
            throw new IllegalArgumentException("Unsupported desktop JSON_SEARCH query");
        }
        JsonNode value = JSON.readTree(document);
        if (!value.isArray()) return null;
        Pattern match = likePattern(needle, escape == null ? "\\" : escape);
        for (int i = 0; i < value.size(); i++) {
            JsonNode candidate = value.get(i).path(field.group(1));
            if (candidate.isTextual() && match.matcher(candidate.textValue()).matches()) {
                return JSON.writeValueAsString("$[" + i + "]." + field.group(1));
            }
        }
        return null;
    }

    private static Pattern likePattern(String text, String escape) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (!escape.isEmpty() && current == escape.charAt(0) && i + 1 < text.length()) {
                regex.append(Pattern.quote(String.valueOf(text.charAt(++i))));
            } else if (current == '%') regex.append(".*");
            else if (current == '_') regex.append('.');
            else regex.append(Pattern.quote(String.valueOf(current)));
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }
}
