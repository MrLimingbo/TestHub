package io.testhub.platform.functional;

import java.time.Instant;

public record FunctionalCase(String id, String organizationId, String projectId, String catalogId,
                             String requirementId, String caseCode, String title, String priority, String status,
                             String precondition, String steps, String expectedResult, String createdBy,
                             String updatedBy, Instant createdAt, Instant updatedAt) {
}
