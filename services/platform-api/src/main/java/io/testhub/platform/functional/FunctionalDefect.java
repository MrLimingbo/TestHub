package io.testhub.platform.functional;

import java.time.Instant;

public record FunctionalDefect(String id, String organizationId, String projectId, String defectCode, String title,
                               String severity, String status, String source, String externalUrl, String description,
                               String createdBy, String updatedBy, Instant createdAt, Instant updatedAt, int linkCount) { }
