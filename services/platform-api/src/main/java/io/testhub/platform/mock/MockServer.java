package io.testhub.platform.mock;

import java.time.Instant;

public record MockServer(String id, String organizationId, String projectId, String publicKey, String name, boolean enabled,
                         boolean smartExampleEnabled, String createdBy, Instant createdAt, Instant updatedAt, int ruleCount) {
}
