package io.testhub.platform.apiautomation;

import java.util.List;

/** Export-safe report. It excludes URLs, bodies, headers, variable values, secret names and log messages. */
public record ApiAutomationExecutionReport(String contract, int reportVersion, ApiAutomationExecution execution,
                                           ManifestSummary manifest, RuntimeSummary runtime,
                                           FailureStrategySummary failureStrategy, ResultSummary summary,
                                           List<ParallelGroupSummary> parallelGroups,
                                           List<ApiAutomationExecutionHttpResult> httpResults,
                                           List<ApiAutomationExecutionStepResult> stepResults,
                                           List<ApiAutomationExecutionScriptResult> scriptResults) {
    public ApiAutomationExecutionReport {
        parallelGroups = List.copyOf(parallelGroups == null ? List.of() : parallelGroups);
        httpResults = List.copyOf(httpResults == null ? List.of() : httpResults);
        stepResults = List.copyOf(stepResults == null ? List.of() : stepResults);
        scriptResults = List.copyOf(scriptResults == null ? List.of() : scriptResults);
    }

    public record ManifestSummary(String checksum, int stepCount, int variableCount, int secretReferenceCount,
                                  String environmentCode, String failureStrategy) {
    }

    public record RuntimeSummary(String checksum, int variableCount, int withheldSecretCount, String environmentCode) {
    }

    /** Fixed strategy metadata for CI. It deliberately excludes failure messages, values and user content. */
    public record FailureStrategySummary(String strategy, int continuedFailureCount) {
    }

    /** Parent-step aggregation for bounded parallel groups. It excludes every request configuration and value. */
    public record ParallelGroupSummary(String stepId, int configuredBranchCount, int completedBranchCount,
                                       int succeededBranchCount, int failedBranchCount, int attemptCount,
                                       int retryAttemptCount, Integer firstRequestSequence, Integer lastRequestSequence,
                                       long totalDurationMillis) {
    }

    public record ResultSummary(int httpResultCount, int httpSucceededCount, int httpFailedCount,
                                long httpDurationMillis, int stepResultCount, int assertionPassedCount,
                                int assertionFailedCount, int scriptResultCount, int scriptTestCount,
                                int scriptPassedCount, int scriptFailedCount) {
    }
}
