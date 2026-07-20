package io.testhub.platform.functional;

public record FunctionalTestPlanCase(String id, String planId, String caseId, int sortOrder,
                                     String caseCode, String title, String priority, String status) {
}
