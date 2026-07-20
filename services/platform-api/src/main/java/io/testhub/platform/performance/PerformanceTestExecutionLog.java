package io.testhub.platform.performance;

import java.time.Instant;

public record PerformanceTestExecutionLog(String id, String executionId, String source, String runnerId, String level,
                                          String code, String message, Instant occurredAt, Instant createdAt) {
}
