package io.testhub.platform.parameter;

import java.time.Instant;

public record EnvironmentSecret(String id, String environmentCode, String key, String updatedBy, Instant updatedAt) {
}
