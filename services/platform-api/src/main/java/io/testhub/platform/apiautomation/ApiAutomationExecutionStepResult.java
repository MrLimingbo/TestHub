package io.testhub.platform.apiautomation;

import java.time.Instant;

/** Safe result metadata for a declarative extract or status-code assertion; it excludes values and response content. */
public record ApiAutomationExecutionStepResult(String id, String executionId, String stepId, int stepSequence,
                                               String stepType, String outcome, int observedStatusCode,
                                               String runnerId, Instant occurredAt, Instant createdAt) {
}
