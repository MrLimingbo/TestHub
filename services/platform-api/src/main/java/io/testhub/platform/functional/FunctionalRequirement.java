package io.testhub.platform.functional;

import java.time.Instant;

public record FunctionalRequirement(String id, String organizationId, String projectId, String catalogId,
                                    String requirementCode, String title, String priority, String status,
                                    String description, String createdBy, String updatedBy,
                                    Instant createdAt, Instant updatedAt) {
}
