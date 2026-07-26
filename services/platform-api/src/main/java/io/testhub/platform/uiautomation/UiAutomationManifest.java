package io.testhub.platform.uiautomation;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record UiAutomationManifest(String id, String executionId, String scenarioId, int scenarioVersion, Snapshot snapshot,
                                   String checksum, Instant createdAt) {
    public record Snapshot(String contract, int manifestVersion, BrowserProfile browserProfile, Scenario scenario) { }
    public record BrowserProfile(String name, String browser, String baseUrl, int viewportWidth, int viewportHeight) { }
    public record Scenario(String name, String failureStrategy, Map<String, String> variables, List<ResolvedStep> steps) { }
    public record ResolvedStep(String id, String type, String name, String selector, String value, String url,
                               String expectedText, Integer timeoutMillis) { }
}
