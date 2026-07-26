package io.testhub.platform.apiautomation;

import java.time.Instant;

/**
 * Redacted per-request outcome. URLs, request bodies, response headers and response bodies are deliberately absent.
 */
public record ApiAutomationExecutionHttpResult(String id, String executionId, String stepId, int requestSequence, int attemptNumber,
                                                String method, String outcome, Integer statusCode, int durationMillis,
                                                String runnerId, Instant occurredAt, Instant createdAt) {
}
