package io.testhub.platform.execution;

import java.time.Instant;

public record ExecutionTask(String id, String organizationId, String projectId, String taskType, String name,
                            String status, int progress, String requestedBy, Instant createdAt, Instant updatedAt) {
}
