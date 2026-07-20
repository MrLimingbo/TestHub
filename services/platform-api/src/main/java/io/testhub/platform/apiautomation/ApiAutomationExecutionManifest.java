package io.testhub.platform.apiautomation;

import java.time.Instant;
import java.util.List;

/**
 * Immutable, non-sensitive execution blueprint. It intentionally excludes every variable and step-config value.
 */
public record ApiAutomationExecutionManifest(String id, String executionId, String scenarioId, int scenarioVersion,
                                             String checksum, int stepCount, int variableCount,
                                             int secretReferenceCount, Snapshot snapshot, Instant createdAt) {
    public record Snapshot(String contract, int manifestVersion, String environmentCode, String failureStrategy,
                           List<Step> steps, List<VariableBinding> variableBindings,
                           List<VariableReference> variableReferences, ScriptSummary scripts) {
        public Snapshot {
            steps = List.copyOf(steps == null ? List.of() : steps);
            variableBindings = List.copyOf(variableBindings == null ? List.of() : variableBindings);
            variableReferences = List.copyOf(variableReferences == null ? List.of() : variableReferences);
            scripts = scripts == null ? new ScriptSummary(false, null, false, null) : scripts;
        }
    }

    /** Hashes prove the revision used for an execution without retaining user-authored source in the manifest. */
    public record ScriptSummary(boolean preRequestConfigured, String preRequestChecksum,
                                boolean postResponseConfigured, String postResponseChecksum) {
    }

    public record Step(String id, String type, String name, String requestMethod, int parallelRequestCount,
                       List<String> parallelRequestMethods, List<String> configKeys) {
        public Step {
            parallelRequestMethods = List.copyOf(parallelRequestMethods == null ? List.of() : parallelRequestMethods);
            configKeys = List.copyOf(configKeys == null ? List.of() : configKeys);
        }
    }

    public record VariableBinding(String name, String source, boolean secret) {
    }

    public record VariableReference(String name, String source, boolean secret, List<ReferenceLocation> locations) {
        public VariableReference {
            locations = List.copyOf(locations == null ? List.of() : locations);
        }
    }

    public record ReferenceLocation(String stepId, String configKey) {
    }
}
