package io.testhub.platform.apiautomation;

import java.time.Instant;

public record ApiAutomationExecutionProfile(String id, String organizationId, String projectId, String name,
                                            String environmentCode, int version, int variableCount, String createdBy,
                                            Instant createdAt, Instant updatedAt) {
}
