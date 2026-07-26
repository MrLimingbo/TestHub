package io.testhub.platform.apiautomation;

import java.time.Instant;

public record ApiRunnerRuntimeLease(String lease, String executionId, String taskId, String runnerId,
                                    Instant expiresAt, String purpose) {
}
