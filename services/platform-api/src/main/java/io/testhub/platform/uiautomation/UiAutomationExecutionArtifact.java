package io.testhub.platform.uiautomation;

import java.time.Instant;

public record UiAutomationExecutionArtifact(String id, String executionId, String artifactType, String originalName,
                                            String contentType, long sizeBytes, String checksum, String runnerId,
                                            Instant createdAt) { }
