package io.testhub.platform.apiautomation;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Short-lived, Runner-only HTTP artifact. It is generated on demand and is never stored in Redis or execution logs.
 */
public record ApiRunnerRuntimeConfiguration(String contract, int artifactVersion, String executionId, String taskId,
                                            Instant deadlineAt, String failureStrategy, Map<String, String> variables, Scripts scripts,
                                            List<Step> steps) {
    public ApiRunnerRuntimeConfiguration {
        failureStrategy = failureStrategy == null ? "STOP" : failureStrategy;
        variables = Map.copyOf(new LinkedHashMap<>(variables == null ? Map.of() : variables));
        scripts = scripts == null ? new Scripts("", "") : scripts;
        steps = List.copyOf(steps == null ? List.of() : steps);
    }

    /**
     * Runner-only declarative instruction. Null fields are intentional for step types that do not use them.
     * The artifact is ephemeral and never persisted or sent through a Redis task stream.
     */
    public record Step(String stepId, String type, String method, String url, String body, String contentType,
                       Integer expectedStatusCode, String variableName, Long delayMillis, String conditionOperator,
                       Integer loopTotalRuns, Integer maxAttempts, List<ParallelRequest> parallelRequests) {
        public Step {
            parallelRequests = List.copyOf(parallelRequests == null ? List.of() : parallelRequests);
        }
    }

    /** Fixed, independent request instruction inside a bounded parallel group. */
    public record ParallelRequest(String method, String url, String body, String contentType, Integer maxAttempts) {
    }

    /** Script source is delivered only over the signed, short-lived Runner configuration request. */
    public record Scripts(String preRequestScript, String postResponseScript) {
        public Scripts {
            preRequestScript = preRequestScript == null ? "" : preRequestScript;
            postResponseScript = postResponseScript == null ? "" : postResponseScript;
        }
    }
}
