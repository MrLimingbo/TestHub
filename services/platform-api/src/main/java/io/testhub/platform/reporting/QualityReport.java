package io.testhub.platform.reporting;

import io.testhub.platform.dashboard.ProjectDashboard;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Read-only quality view across the projects the caller may access. */
public record QualityReport(LocalDate from, LocalDate to, Instant generatedAt, int projectCount,
                            Summary summary, List<ProjectDashboard.TrendPoint> trend,
                            List<ProjectHealth> projects) {
    public record Summary(long functionalCaseCount, long apiDefinitionCount, long automationScenarioCount,
                          long openDefectCount, long criticalDefectCount, long completedExecutions,
                          long passedExecutions, long failedExecutions, Integer passRatePercent) {
    }

    public record ProjectHealth(String projectId, String projectName, String projectCode,
                                long completedExecutions, long passedExecutions, long failedExecutions,
                                Integer passRatePercent, long openDefectCount, long criticalDefectCount,
                                String healthStatus) {
    }
}
