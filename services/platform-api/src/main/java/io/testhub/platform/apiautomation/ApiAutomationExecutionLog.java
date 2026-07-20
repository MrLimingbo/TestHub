package io.testhub.platform.apiautomation;

import java.time.Instant;

public record ApiAutomationExecutionLog(String id, String executionId, String source, String runnerId, String level,
                                        String code, String message, Instant occurredAt, Instant createdAt) {
}
