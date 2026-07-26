package io.testhub.platform.mobileautomation;

import java.time.Instant;

public record MobileAutomationExecutionArtifact(String id, String executionId, String artifactType, String originalName,
                                                 String contentType, long sizeBytes, String checksum, String runnerId,
                                                 Instant createdAt) { }
