package io.testhub.platform.uiautomation;

import java.time.Instant;

public record UiAutomationPageObject(String id, String organizationId, String projectId, String name, String selectorValue,
                                     String description, String updatedBy, Instant createdAt, Instant updatedAt) { }
