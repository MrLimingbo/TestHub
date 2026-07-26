package io.testhub.platform.uiautomation;

import java.time.Instant;

public record UiAutomationRunnerStatus(String runnerId, String runnerType, String protocolVersion, String runnerVersion,
                                       boolean executionEnabled, boolean trusted, String trustStatus, int maxConcurrentTasks,
                                       int activeTaskCount, Instant startedAt, Instant heartbeatAt) { }
