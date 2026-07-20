package io.testhub.platform.performance;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Runtime-only artifact delivered to one verified Runner after task dispatch. */
public record PerformanceRunnerRuntimeConfiguration(String contract, int artifactVersion, String executionId,
                                                    String taskId, Instant deadlineAt,
                                                    List<ThreadGroup> threadGroups) {
    public record ThreadGroup(String name, String targetUrl, String loadModel, int threadCount,
                              int rampUpSeconds, Integer stepCount, Integer stepIntervalSeconds, int durationSeconds, Integer targetTps,
                              String requestMethod, Map<String, String> requestHeaders, Map<String, String> queryParameters, String requestBody,
                              Integer expectedStatusCode, String responseContains, String dataSetCsv,
                              Integer rendezvousUsers) {
        public ThreadGroup {
            requestHeaders = Map.copyOf(requestHeaders == null ? Map.of() : requestHeaders);
            queryParameters = Map.copyOf(queryParameters == null ? Map.of() : queryParameters);
        }
    }
}
