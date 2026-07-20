package io.testhub.platform.performance;

import java.math.BigDecimal;
import java.time.Instant;

public record PerformanceTestExecutionResourceMetric(String id, String executionId, String runnerId,
                                                     BigDecimal cpuPercent, long residentMemoryBytes,
                                                     Instant occurredAt) {
}
