package io.testhub.platform.functional;

import java.time.Instant;

public record FunctionalTestPlan(String id, String organizationId, String projectId, String planCode, String name,
                                 String status, String description, String createdBy, String updatedBy,
                                 Instant createdAt, Instant updatedAt, int caseCount) {
}
