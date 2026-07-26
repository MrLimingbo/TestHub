package io.testhub.platform.mock;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record MockRuntimeRequest(String method, String path, Map<String, List<String>> query, Map<String, String> headers,
                                 String body) {
    public MockRuntimeRequest {
        method = method == null ? "" : method.toUpperCase(Locale.ROOT);
        path = path == null || path.isBlank() ? "/" : path;
        query = query == null ? Map.of() : copyQuery(query);
        headers = headers == null ? Map.of() : copyHeaders(headers);
        body = body == null ? "" : body;
    }

    private static Map<String, List<String>> copyQuery(Map<String, List<String>> source) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        source.forEach((name, values) -> result.put(name, values == null ? List.of() : List.copyOf(values)));
        return Map.copyOf(result);
    }

    private static Map<String, String> copyHeaders(Map<String, String> source) {
        Map<String, String> result = new LinkedHashMap<>();
        source.forEach((name, value) -> result.put(name.toLowerCase(Locale.ROOT), value == null ? "" : value));
        return Map.copyOf(result);
    }
}
