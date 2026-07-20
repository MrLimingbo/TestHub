package io.testhub.platform.functional;

public record FunctionalTestRunStep(String id, String runCaseId, int stepNo, String stepText,
                                    String status, String actualResult) {
}
