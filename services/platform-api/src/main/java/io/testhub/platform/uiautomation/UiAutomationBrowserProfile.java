package io.testhub.platform.uiautomation;

import java.time.Instant;

public record UiAutomationBrowserProfile(String id, String organizationId, String projectId, String name, String browser,
                                         String baseUrl, int viewportWidth, int viewportHeight, boolean enabled, int version,
                                         String updatedBy, Instant createdAt, Instant updatedAt) { }
