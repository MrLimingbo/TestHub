package io.testhub.platform.performance;

import java.time.Instant;

public record PerformanceTestExecutionError(String id, String executionId, String runnerId, String threadGroupName,
                                            String responseCode, long errorCount, Instant occurredAt) {
}
