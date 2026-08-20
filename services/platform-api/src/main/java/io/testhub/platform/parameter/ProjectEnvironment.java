package io.testhub.platform.parameter;

import java.time.Instant;

public record ProjectEnvironment(
        String id,
        String organizationId,
        String projectId,
        String name,
        String environmentCode,
        String baseUrl,
        String description,
        boolean enabled,
        boolean defaultEnvironment,
        int variableCount,
        int secretCount,
        int version,
        String createdBy,
        String updatedBy,
        Instant createdAt,
        Instant updatedAt) {
}
