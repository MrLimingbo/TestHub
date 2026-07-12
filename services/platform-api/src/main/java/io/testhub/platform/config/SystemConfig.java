package io.testhub.platform.config;

import java.time.Instant;

public record SystemConfig(String key, String value, boolean secret, String updatedBy, Instant updatedAt) {
}
