package io.testhub.platform.performance;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Immutable plan snapshot. It is kept off the Redis message boundary. */
public record PerformanceTestExecutionManifest(String id, String executionId, String planId, int planVersion,
                                               String checksum, int threadGroupCount, Snapshot snapshot,
                                               Instant createdAt) {
    public record Snapshot(String contract, int manifestVersion, String planName, List<ThreadGroup> threadGroups) {
        public Snapshot {
            threadGroups = List.copyOf(threadGroups == null ? List.of() : threadGroups);
        }
    }

    public record ThreadGroup(String name, String targetUrl, String loadModel, int threadCount, int rampUpSeconds,
                              Integer stepCount, Integer stepIntervalSeconds, int durationSeconds, Integer targetTps, Integer p95ThresholdMillis,
                              java.math.BigDecimal errorThresholdPercent, String requestMethod,
                              Map<String, String> requestHeaders, Map<String, String> queryParameters, String requestBody,
                              Integer expectedStatusCode, String responseContains, String dataSetCsv,
                              Integer rendezvousUsers) {
        public ThreadGroup {
            requestHeaders = Map.copyOf(requestHeaders == null ? Map.of() : requestHeaders);
            queryParameters = Map.copyOf(queryParameters == null ? Map.of() : queryParameters);
        }
    }
}
