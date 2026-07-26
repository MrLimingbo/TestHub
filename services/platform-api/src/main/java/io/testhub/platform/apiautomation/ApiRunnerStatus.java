package io.testhub.platform.apiautomation;

import java.time.Instant;

public record ApiRunnerStatus(String runnerId, String runnerType, String protocolVersion, String runnerVersion,
                              boolean executionEnabled, boolean trusted, String trustStatus, Instant startedAt,
                              Instant heartbeatAt) {
}
