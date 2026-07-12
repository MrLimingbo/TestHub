package io.testhub.platform.parameter;

import java.time.Instant;

public record ProjectParameter(String id, String environmentCode, String key, String value, boolean secret,
                               String updatedBy, Instant updatedAt) {
}
