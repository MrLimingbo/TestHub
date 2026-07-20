package io.testhub.platform.apiautomation;

import java.time.Instant;

/** A fixed aggregate from an isolated pm.test script run; it intentionally excludes test names and user values. */
public record ApiAutomationExecutionScriptResult(String id, String executionId, String phase, int testCount,
                                                 int passedCount, int failedCount, String runnerId,
                                                 Instant occurredAt, Instant createdAt) {
}
