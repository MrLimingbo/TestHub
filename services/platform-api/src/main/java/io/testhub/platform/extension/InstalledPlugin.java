package io.testhub.platform.extension;

import java.time.Instant;
import java.util.List;

public record InstalledPlugin(String id, String pluginKey, String displayName, String pluginType, String activeVersion,
                              String status, String signerKeyId, String packageChecksum, List<String> permissions,
                              String entrypoint, Instant installedAt, Instant updatedAt, Instant enabledAt,
                              Instant disabledAt) {
}
