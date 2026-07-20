package io.testhub.platform.performance;

import java.time.Instant;

public record PerformanceTestPlan(String id, String organizationId, String projectId, String name, String description,
                                  String status, int version, int threadGroupCount, String createdBy,
                                  Instant createdAt, Instant updatedAt) {
}
