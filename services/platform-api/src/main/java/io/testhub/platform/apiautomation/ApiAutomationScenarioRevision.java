package io.testhub.platform.apiautomation;

import java.time.Instant;

public record ApiAutomationScenarioRevision(String id, String scenarioId, int version, String createdBy, Instant createdAt) {
}
