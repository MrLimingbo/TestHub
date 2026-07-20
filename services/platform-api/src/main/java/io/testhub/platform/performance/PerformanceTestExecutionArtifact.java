package io.testhub.platform.performance;

import java.time.Instant;

/** Immutable, project-scoped raw artifact retained outside the control-plane database. */
public record PerformanceTestExecutionArtifact(String id, String executionId, String artifactType, String originalName,
                                               String contentType, long sizeBytes, String checksum, String runnerId,
                                               Instant createdAt) {
}
