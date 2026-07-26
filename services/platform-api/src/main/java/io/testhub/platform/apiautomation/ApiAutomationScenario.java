package io.testhub.platform.apiautomation;

import java.time.Instant;

public record ApiAutomationScenario(String id, String organizationId, String projectId, String name, String description,
                                    String environmentCode, String failureStrategy, int version, int stepCount,
                                    String createdBy, Instant createdAt, Instant updatedAt) {
}
