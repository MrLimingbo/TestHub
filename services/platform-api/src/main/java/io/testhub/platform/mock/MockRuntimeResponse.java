package io.testhub.platform.mock;

import java.util.Map;

public record MockRuntimeResponse(int status, Map<String, String> headers, String body) {
    public MockRuntimeResponse {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        body = body == null ? "" : body;
    }
}
