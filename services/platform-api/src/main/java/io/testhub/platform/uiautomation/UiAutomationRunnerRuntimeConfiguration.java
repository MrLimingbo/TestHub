package io.testhub.platform.uiautomation;

import java.time.Instant;
import java.util.Map;
import java.util.List;

/** The only value-bearing document a verified Runner receives; task streams contain identifiers only. */
public record UiAutomationRunnerRuntimeConfiguration(String contract, int version, String executionId, String taskId,
                                                     Instant deadlineAt, Browser browser, Scenario scenario) {
    public record Browser(String browser, String baseUrl, int viewportWidth, int viewportHeight) { }
    public record Scenario(String failureStrategy, Map<String, String> variables, List<UiAutomationManifest.ResolvedStep> steps) { }
}
