package io.testhub.platform.performance;

import java.time.Instant;

public record PerformanceRunnerStatus(String runnerId, String runnerType, String protocolVersion, String runnerVersion,
                                      boolean executionEnabled, boolean trusted, String trustStatus, int maxConcurrentTasks,
                                      int activeTaskCount, Instant startedAt,
                                      Instant heartbeatAt) {
}
