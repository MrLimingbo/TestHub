package io.testhub.platform.apiautomation;

import java.time.Instant;

/** Project-scoped, persisted schedule metadata. Quartz jobs are rehydrated from this record at startup. */
public record ApiAutomationSchedule(String id, String organizationId, String projectId, String scenarioId, String name,
                                    String cronExpression, String timezone, int timeoutSeconds,
                                    int maxConsecutiveFailures, int consecutiveFailures, boolean enabled,
                                    String lastExecutionId, String lastExecutionStatus, Instant lastTriggeredAt,
                                    String createdBy, Instant createdAt, Instant updatedAt) {
}
