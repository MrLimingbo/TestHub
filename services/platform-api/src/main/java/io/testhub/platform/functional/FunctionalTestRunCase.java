package io.testhub.platform.functional;

public record FunctionalTestRunCase(String id, String runId, String caseId, String caseCode, String title,
                                    String status, String note) {
}
