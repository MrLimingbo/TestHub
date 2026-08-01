package io.testhub.platform.access;

import java.time.Instant;

public record ProjectSummary(String id, String organizationId, String name, String code, String status,
                             String description, String createdByName, Instant createdAt, Instant updatedAt) {
}
