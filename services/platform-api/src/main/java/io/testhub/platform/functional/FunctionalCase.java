package io.testhub.platform.functional;

import java.time.Instant;

public record FunctionalCase(String id, long displayId, String organizationId, String projectId, String catalogId,
                             String requirementId, String caseCode, String caseType, String title, String priority, String status,
                             String precondition, String steps, String expectedResult, String createdBy,
                             String createdByName, String updatedBy, Instant createdAt, Instant updatedAt,
                             String executedBy, String executedByName, Instant executedAt) {
}
