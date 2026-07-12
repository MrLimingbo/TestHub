package io.testhub.platform.notification;

import java.time.Instant;

public record NotificationTemplate(String id, String organizationId, String projectId, String name, String eventCode,
                                   String channel, String subjectTemplate, String bodyTemplate, boolean enabled,
                                   String updatedBy, Instant updatedAt) {
}
