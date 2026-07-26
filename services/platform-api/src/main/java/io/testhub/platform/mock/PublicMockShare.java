package io.testhub.platform.mock;

import java.time.Instant;

record PublicMockShare(String shareKey, String shareName, Instant expiresAt, String serverId, String serverName,
                       String publicKey, boolean serverEnabled, boolean smartExampleEnabled) {
}
