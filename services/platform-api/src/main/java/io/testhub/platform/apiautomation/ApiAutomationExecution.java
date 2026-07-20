package io.testhub.platform.apiautomation;

import java.time.Instant;

public record ApiAutomationExecution(String id, String taskId, String organizationId, String projectId,
                                     String scenarioId, String scenarioName, int scenarioVersion, String executionProfileId,
                                     Integer executionProfileVersion, String dataSetId, Integer dataSetRowNumber,
                                     String status, String statusCode, String statusMessage,
                                     String manifestRef, String runnerId, String requestedBy, Instant requestedAt,
                                     Instant deadlineAt, Instant startedAt, Instant finishedAt, Instant createdAt,
                                     Instant updatedAt) {
}
