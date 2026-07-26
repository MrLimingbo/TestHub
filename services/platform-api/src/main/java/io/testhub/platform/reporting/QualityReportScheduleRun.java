package io.testhub.platform.reporting;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record QualityReportScheduleRun(String id, String scheduleId, String organizationId, List<String> projectIds,
                                       LocalDate from, LocalDate to, String status, int deliveryCount,
                                       QualityReport.Summary summary, String errorMessage, Instant startedAt,
                                       Instant finishedAt) {
}
