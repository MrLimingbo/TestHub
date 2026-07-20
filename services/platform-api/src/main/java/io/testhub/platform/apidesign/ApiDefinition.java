package io.testhub.platform.apidesign;

import java.time.Instant;

public record ApiDefinition(String id, String organizationId, String projectId, String catalogId, String method, String path,
                            String name, String status, String currentVersionId, String createdBy, String updatedBy,
                            Instant createdAt, Instant updatedAt, int versionCount) { }
