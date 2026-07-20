package io.testhub.platform.functional;

import java.time.Instant;

public record FunctionalCustomField(String id, String organizationId, String projectId, String assetType, String name,
                                    String fieldKey, String fieldType, boolean required, String optionsJson,
                                    String createdBy, String updatedBy, Instant createdAt, Instant updatedAt) { }
