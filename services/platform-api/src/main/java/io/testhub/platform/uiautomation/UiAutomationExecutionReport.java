package io.testhub.platform.uiautomation;

import java.util.List;

public record UiAutomationExecutionReport(UiAutomationExecution execution, Summary summary,
                                          List<UiAutomationExecutionStepResult> steps,
                                          List<UiAutomationExecutionArtifact> artifacts) {
    public record Summary(int totalSteps, int passedSteps, int failedSteps, long totalDurationMillis) { }
}
