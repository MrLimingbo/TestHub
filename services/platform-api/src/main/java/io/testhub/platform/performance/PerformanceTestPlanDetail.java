package io.testhub.platform.performance;

import java.time.Instant;
import java.util.List;

public record PerformanceTestPlanDetail(String id, String organizationId, String projectId, String name,
                                        String description, String status, int version, String createdBy,
                                        Instant createdAt, Instant updatedAt,
                                        List<PerformanceTestThreadGroup> threadGroups) {
    public PerformanceTestPlanDetail {
        threadGroups = List.copyOf(threadGroups == null ? List.of() : threadGroups);
    }
}
