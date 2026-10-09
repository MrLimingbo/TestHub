package io.testhub.platform.operations;

import java.time.Instant;
import java.util.List;

/** Public projection for system administrators; it intentionally contains no endpoints, credentials, or stack traces. */
public record OperationsOverview(String status, Instant checkedAt, List<Component> components, List<Control> controls,
                                DataProtection dataProtection, CapacityProfile capacity, String runtimeMode) {
    public record Component(String key, String label, String status, String detail) { }
    public record Control(String key, String label, String status, String detail) { }
    public record DataProtection(String migrationVersion, String migrationStatus, String migrationDetail,
                                 List<Control> controls) { }
    public record CapacityProfile(String status, String summary, List<Control> controls) { }
}
