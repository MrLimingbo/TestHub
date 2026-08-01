package io.testhub.platform.apidesign;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record ApiDefinitionCase(String id, String organizationId, String projectId, String apiId, String name,
                                String environmentCode, JsonNode requestSnapshot, Integer expectedStatus,
                                String responseValidation, String savedResponseBody, String createdBy, String updatedBy,
                                Instant createdAt, Instant updatedAt) {
}
