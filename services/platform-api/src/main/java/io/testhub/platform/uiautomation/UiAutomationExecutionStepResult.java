package io.testhub.platform.uiautomation;

import java.time.Instant;

public record UiAutomationExecutionStepResult(String id, String executionId, String runnerId, String stepId, int stepSequence,
                                              String stepType, String stepName, String outcome, int durationMillis,
                                              String errorMessage, Instant occurredAt) { }
