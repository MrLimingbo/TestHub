package io.testhub.platform.apiautomation;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ApiAutomationScenarioDetail(String id, String organizationId, String projectId, String name, String description,
                                          String environmentCode, String failureStrategy, int version, List<AutomationStep> steps,
                                          Map<String, String> variables, String preRequestScript, String postResponseScript,
                                          String createdBy, Instant createdAt, Instant updatedAt) {
    public ApiAutomationScenarioDetail {
        steps = List.copyOf(steps == null ? List.of() : steps);
        variables = Map.copyOf(new LinkedHashMap<>(variables == null ? Map.of() : variables));
        preRequestScript = preRequestScript == null ? "" : preRequestScript;
        postResponseScript = postResponseScript == null ? "" : postResponseScript;
    }
}
