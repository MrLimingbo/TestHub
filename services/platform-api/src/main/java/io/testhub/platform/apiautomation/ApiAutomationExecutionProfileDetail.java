package io.testhub.platform.apiautomation;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record ApiAutomationExecutionProfileDetail(String id, String organizationId, String projectId, String name,
                                                  String environmentCode, int version, Map<String, String> variables,
                                                  String createdBy, Instant createdAt, Instant updatedAt) {
    public ApiAutomationExecutionProfileDetail {
        variables = Map.copyOf(new LinkedHashMap<>(variables == null ? Map.of() : variables));
    }
}
