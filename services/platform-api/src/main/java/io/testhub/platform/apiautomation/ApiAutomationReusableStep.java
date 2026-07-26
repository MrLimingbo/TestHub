package io.testhub.platform.apiautomation;

import java.time.Instant;

public record ApiAutomationReusableStep(String id, String organizationId, String projectId, String name,
                                        String description, int version, int stepCount, String createdBy,
                                        Instant createdAt, Instant updatedAt) {
}
