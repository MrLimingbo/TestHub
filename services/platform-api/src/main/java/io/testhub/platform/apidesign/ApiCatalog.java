package io.testhub.platform.apidesign;

import java.time.Instant;

public record ApiCatalog(String id, String organizationId, String projectId, String parentId, String name, int sortOrder,
                         String createdBy, Instant createdAt) { }
