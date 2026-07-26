package io.testhub.platform.performance;

import java.time.Instant;

public record PerformanceTestExecution(String id, String taskId, String organizationId, String projectId,
                                       String planId, String planName, int planVersion, String status,
                                       String statusCode, String statusMessage, String manifestRef, String runnerId,
                                       int routingAttempts,
                                       String requestedBy, Instant requestedAt, Instant deadlineAt, Instant startedAt,
                                       Instant finishedAt, Instant createdAt, Instant updatedAt) {
}
