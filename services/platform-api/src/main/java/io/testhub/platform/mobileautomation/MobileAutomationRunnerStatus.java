package io.testhub.platform.mobileautomation;

import java.time.Instant;

public record MobileAutomationRunnerStatus(String runnerId, String runnerType, String protocolVersion, String runnerVersion,
                                           boolean executionEnabled, boolean trusted, String trustStatus, int maxConcurrentTasks,
                                           int activeTaskCount, Instant startedAt, Instant heartbeatAt) { }
