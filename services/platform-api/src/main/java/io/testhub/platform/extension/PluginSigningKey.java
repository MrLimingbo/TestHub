package io.testhub.platform.extension;

import java.time.Instant;

/** Public signing-key metadata. The encoded public key is intentionally never returned after registration. */
public record PluginSigningKey(String id, String keyId, String displayName, boolean enabled, Instant createdAt,
                               Instant revokedAt) {
}
