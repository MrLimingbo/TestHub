package io.testhub.platform.apiautomation;

import java.time.Instant;
import java.util.List;

public record ApiAutomationReusableStepDetail(String id, String organizationId, String projectId, String name,
                                              String description, int version, List<AutomationStep> steps,
                                              String createdBy, Instant createdAt, Instant updatedAt) {
    public ApiAutomationReusableStepDetail {
        steps = List.copyOf(steps == null ? List.of() : steps);
    }
}
