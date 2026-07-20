package io.testhub.platform.apiautomation;

import java.time.Instant;
import java.util.List;

/**
 * Internal immutable runtime input snapshot. Environment-secret values are deliberately absent.
 */
public record ApiAutomationExecutionRuntimeSnapshot(String id, String executionId, String checksum, int variableCount,
                                                     int withheldSecretCount, Snapshot snapshot, Instant createdAt) {
    public record Snapshot(String contract, int snapshotVersion, String environmentCode,
                           List<Variable> variables, List<String> withheldSecretNames) {
        public Snapshot {
            variables = List.copyOf(variables == null ? List.of() : variables);
            withheldSecretNames = List.copyOf(withheldSecretNames == null ? List.of() : withheldSecretNames);
        }
    }

    public record Variable(String name, String source, String value) {
    }
}
