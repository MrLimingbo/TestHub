package io.testhub.platform.access;

import java.time.Instant;
import java.util.List;

public record ProjectAccessToken(String id, String name, String tokenPrefix, List<String> scopes, Instant expiresAt,
                                 Instant lastUsedAt, Instant revokedAt, Instant createdAt) {
}
