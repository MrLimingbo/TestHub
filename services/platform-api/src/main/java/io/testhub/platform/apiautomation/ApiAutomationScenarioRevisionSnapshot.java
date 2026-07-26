package io.testhub.platform.apiautomation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable scenario source captured by the scenario revision table. */
public record ApiAutomationScenarioRevisionSnapshot(String name, String description, String environmentCode,
                                                    String failureStrategy, List<AutomationStep> steps,
                                                    Map<String, String> variables, String preRequestScript,
                                                    String postResponseScript) {
    public ApiAutomationScenarioRevisionSnapshot {
        steps = List.copyOf(steps == null ? List.of() : steps);
        variables = Map.copyOf(new LinkedHashMap<>(variables == null ? Map.of() : variables));
        preRequestScript = preRequestScript == null ? "" : preRequestScript;
        postResponseScript = postResponseScript == null ? "" : postResponseScript;
    }
}
