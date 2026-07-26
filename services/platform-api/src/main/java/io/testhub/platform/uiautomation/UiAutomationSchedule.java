package io.testhub.platform.uiautomation;

import java.time.Instant;

/** Persisted project schedule. Quartz only stores its immutable schedule id. */
public record UiAutomationSchedule(String id, String organizationId, String projectId, String scenarioId,
                                   String functionalPlanId, String functionalPlanName, String name,
                                   String cronExpression, String timezone, int timeoutSeconds, int maxRetryAttempts,
                                   int retryBackoffSeconds, boolean enabled, String lastExecutionId,
                                   String lastExecutionStatus, Instant lastTriggeredAt, String createdBy,
                                   Instant createdAt, Instant updatedAt) { }
