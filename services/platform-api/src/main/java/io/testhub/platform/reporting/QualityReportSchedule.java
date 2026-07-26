package io.testhub.platform.reporting;

import java.time.Instant;
import java.util.List;

/** Persisted Quartz schedule for a permission-scoped quality report. */
public record QualityReportSchedule(String id, String organizationId, String name, List<String> projectIds,
                                    String cronExpression, String timezone, int periodDays, boolean enabled,
                                    String lastRunId, String lastRunStatus, Instant lastRunAt, String createdBy,
                                    Instant createdAt, Instant updatedAt) {
}
