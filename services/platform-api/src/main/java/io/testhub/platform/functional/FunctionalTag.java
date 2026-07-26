package io.testhub.platform.functional;

import java.time.Instant;

public record FunctionalTag(String id, String organizationId, String projectId, String name, String color,
                            String createdBy, Instant createdAt) { }
