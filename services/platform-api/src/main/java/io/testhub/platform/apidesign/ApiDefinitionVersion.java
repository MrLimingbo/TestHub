package io.testhub.platform.apidesign;

import java.time.Instant;

public record ApiDefinitionVersion(String id, String apiId, String versionName, String summary, String description,
                                   String requestHeaders, String requestQuery, String requestPath, String requestBody,
                                   String responseBody, String exampleResponse, String status, String publishedBy,
                                   Instant publishedAt, String createdBy, Instant createdAt) { }
