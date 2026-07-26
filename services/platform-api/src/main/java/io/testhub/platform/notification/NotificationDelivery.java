package io.testhub.platform.notification;

import java.time.Instant;

/** Immutable notification attempt metadata. Payloads and signing secrets are intentionally never exposed here. */
public record NotificationDelivery(String id, String scheduleRunId, String templateId, String organizationId,
                                   String projectId, String templateName, String eventCode, String channel,
                                   String targetUrl, String subject, String message, String status, int attemptCount,
                                   Instant nextAttemptAt, Instant deliveredAt, Integer responseStatus, String lastError,
                                   Instant createdAt, Instant updatedAt) {
}
