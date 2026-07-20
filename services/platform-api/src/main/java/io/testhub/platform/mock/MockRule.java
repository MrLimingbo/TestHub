package io.testhub.platform.mock;

import java.time.Instant;
import java.util.Map;

public record MockRule(String id, String serverId, String organizationId, String projectId, String name, String httpMethod,
                       String path, int priority, Map<String, String> requestQuery, Map<String, String> requestHeaders,
                       String requestBodyMatch, String requestBody, int responseStatus, Map<String, String> responseHeaders,
                       String responseBody, boolean enabled, String createdBy, Instant createdAt, Instant updatedAt) {
    public MockRule {
        requestQuery = requestQuery == null ? Map.of() : Map.copyOf(requestQuery);
        requestHeaders = requestHeaders == null ? Map.of() : Map.copyOf(requestHeaders);
        requestBodyMatch = requestBodyMatch == null ? "ANY" : requestBodyMatch;
        requestBody = requestBody == null ? "" : requestBody;
        responseHeaders = responseHeaders == null ? Map.of() : Map.copyOf(responseHeaders);
        responseBody = responseBody == null ? "" : responseBody;
    }
}
