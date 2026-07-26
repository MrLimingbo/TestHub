package io.testhub.platform.performance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record PerformanceTestThreadGroup(String id, String planId, String organizationId, String projectId, String name,
                                         String targetUrl, String loadModel, int threadCount, int rampUpSeconds,
                                         Integer stepCount, Integer stepIntervalSeconds, int durationSeconds, Integer targetTps, Integer p95ThresholdMillis,
                                         BigDecimal errorThresholdPercent, String requestMethod,
                                         Map<String, String> requestHeaders, Map<String, String> queryParameters, String requestBody,
                                         Integer expectedStatusCode, String responseContains, String dataSetCsv, Integer rendezvousUsers,
                                         int sortOrder, Instant createdAt,
                                         Instant updatedAt) {
    public PerformanceTestThreadGroup {
        requestHeaders = Map.copyOf(requestHeaders == null ? Map.of() : requestHeaders);
        queryParameters = Map.copyOf(queryParameters == null ? Map.of() : queryParameters);
    }
}
