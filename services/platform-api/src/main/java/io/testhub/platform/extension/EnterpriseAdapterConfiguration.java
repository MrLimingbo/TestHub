package io.testhub.platform.extension;

import java.time.Instant;
import java.util.List;

/** Does not expose decrypted configuration values or credentials. */
public record EnterpriseAdapterConfiguration(String id, String adapterType, String displayName, String endpoint,
                                             List<String> configuredFields, boolean enabled, Instant createdAt,
                                             Instant updatedAt) {
}
