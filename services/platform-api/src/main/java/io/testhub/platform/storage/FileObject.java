package io.testhub.platform.storage;

import java.time.Instant;

public record FileObject(String id, String organizationId, String projectId, String objectKey, String originalName,
                         String contentType, long sizeBytes, String uploadedBy, Instant createdAt) {
}
