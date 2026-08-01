package io.testhub.platform.functional;

import java.time.Instant;

public record FunctionalCatalog(String id, String organizationId, String projectId, String scope, String parentId,
                                String name, int sortOrder, String createdBy, Instant createdAt) {
}
