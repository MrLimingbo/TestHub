package io.testhub.platform.apiautomation;

import java.time.Instant;
import java.util.List;

public record ApiRunnerRuntimeLeaseIndex(String executionId, String taskId, String runnerId, Instant expiresAt,
                                         String purpose, String environmentCode, int scenarioVersion,
                                         String manifestChecksum, boolean runtimeSnapshotAvailable,
                                         String runtimeSnapshotChecksum, int runtimeVariableCount,
                                         int withheldSecretCount, List<VariableReference> variableReferences) {
    public record VariableReference(String name, String source, boolean secret) {
    }
}
