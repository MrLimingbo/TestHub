package io.testhub.platform.performance;

import java.math.BigDecimal;
import java.time.Instant;

public record PerformanceTestExecutionMetric(String id, String executionId, String runnerId, String threadGroupName,
                                             long sampleCount, long successCount, long errorCount, int activeThreads,
                                             BigDecimal throughputPerSecond, int averageLatencyMillis,
                                             int p95LatencyMillis, int minLatencyMillis, int maxLatencyMillis,
                                             Instant occurredAt) {
}
