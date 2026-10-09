package io.testhub.platform.apidebug;

import java.time.Instant;

public record ApiDebugHistory(String id, String organizationId, String projectId, String method, String protocol, String requestUrl,
                              Integer responseStatus, int durationMs, int responseSize, String result,
                              String errorMessage, String replayDraft, String createdBy, Instant createdAt, String importSourceProjectId, String importSourceId) {
    public ApiDebugHistory(String id, String organizationId, String projectId, String method, String protocol, String requestUrl,
                           Integer responseStatus, int durationMs, int responseSize, String result, String errorMessage,
                           String replayDraft, String createdBy, Instant createdAt) {
        this(id, organizationId, projectId, method, protocol, requestUrl, responseStatus, durationMs, responseSize, result,
                errorMessage, replayDraft, createdBy, createdAt, null, null);
    }
}
