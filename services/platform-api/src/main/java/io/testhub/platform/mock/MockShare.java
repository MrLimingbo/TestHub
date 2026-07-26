package io.testhub.platform.mock;

import java.time.Instant;

public record MockShare(String id, String serverId, String organizationId, String projectId, String shareKey, String name,
                        Instant expiresAt, Instant revokedAt, String createdBy, Instant createdAt) {
}
