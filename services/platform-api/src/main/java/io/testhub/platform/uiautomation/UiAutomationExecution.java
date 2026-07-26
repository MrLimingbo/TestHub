package io.testhub.platform.uiautomation;

import java.time.Instant;

public record UiAutomationExecution(String id, String taskId, String organizationId, String projectId, String scenarioId,
                                    String scenarioName, int scenarioVersion, String browserProfileId, String browserProfileName,
                                    String browser, String scheduleId, String functionalPlanId, String functionalPlanName,
                                    String retryOfExecutionId, int retryAttempt, String status, String statusCode, String statusMessage, String runnerId,
                                    String manifestRef, String requestedBy, Instant requestedAt, Instant deadlineAt,
                                    Instant startedAt, Instant finishedAt, Instant createdAt, Instant updatedAt) { }
