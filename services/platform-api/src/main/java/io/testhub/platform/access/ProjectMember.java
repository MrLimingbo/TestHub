package io.testhub.platform.access;

import java.time.Instant;

public record ProjectMember(String membershipId, String userId, String email, String displayName, String roleCode,
                            Instant createdAt) {
}
