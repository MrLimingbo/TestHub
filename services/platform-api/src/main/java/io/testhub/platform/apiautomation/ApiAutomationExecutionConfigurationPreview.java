package io.testhub.platform.apiautomation;

import java.util.List;

/**
 * A transient, value-free view of the variable resolution that would be frozen for a direct scenario execution.
 */
public record ApiAutomationExecutionConfigurationPreview(String scenarioId, int scenarioVersion, String environmentCode,
                                                         String executionProfileId, Integer executionProfileVersion,
                                                         List<VariableReference> variableReferences) {
    public ApiAutomationExecutionConfigurationPreview {
        variableReferences = List.copyOf(variableReferences == null ? List.of() : variableReferences);
    }

    public record VariableReference(String name, String source, boolean secret,
                                    List<ApiAutomationExecutionManifest.ReferenceLocation> locations) {
        public VariableReference {
            locations = List.copyOf(locations == null ? List.of() : locations);
        }
    }
}
