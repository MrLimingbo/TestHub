package io.testhub.platform.functional;

import java.time.Instant;

public record FunctionalTestRun(String id, String organizationId, String projectId, String planId, String name,
                                String status, String createdBy, Instant startedAt, Instant finishedAt,
                                Instant createdAt, Instant updatedAt, int totalCount, int passedCount,
                                int failedCount, int blockedCount, int notRunCount) {
}
