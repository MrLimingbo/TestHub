package io.testhub.platform.apiautomation;

import java.time.Instant;

/** Safe webhook metadata. The HMAC secret is deliberately absent. */
public record ApiAutomationWebhookTrigger(String id, String organizationId, String projectId, String scenarioId,
                                          String name, boolean enabled, boolean secretReady, String createdBy, Instant createdAt,
                                          Instant updatedAt) {
}
