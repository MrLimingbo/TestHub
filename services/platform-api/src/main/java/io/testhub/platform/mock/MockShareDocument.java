package io.testhub.platform.mock;

import java.time.Instant;
import java.util.List;

public record MockShareDocument(String shareKey, String shareName, String serviceName, String publicKey,
                                boolean serviceEnabled, boolean smartExampleEnabled, Instant expiresAt,
                                List<RuleSummary> rules) {
    public record RuleSummary(String name, String httpMethod, String path, int responseStatus, String matchSummary) {
    }
}
