package io.testhub.platform.uiautomation;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record UiAutomationScenario(String id, String organizationId, String projectId, String browserProfileId,
                                   String name, String description, String failureStrategy, int version,
                                   Map<String, String> variables, List<UiAutomationStep> steps, String updatedBy,
                                   Instant createdAt, Instant updatedAt) { }
