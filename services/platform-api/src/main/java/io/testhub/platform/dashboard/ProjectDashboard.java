package io.testhub.platform.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ProjectDashboard(int periodDays, Instant generatedAt, Summary summary, List<TrendPoint> trend,
                               List<DistributionItem> distribution, List<ActivityItem> activities) {
    public record Summary(long functionalCaseCount, long apiDefinitionCount, long automationScenarioCount,
                          long openDefectCount, long criticalDefectCount, long completedExecutions,
                          long passedExecutions, long failedExecutions, Integer passRatePercent,
                          String healthStatus) {
    }

    public record TrendPoint(LocalDate date, long completedExecutions, long passedExecutions,
                             long failedExecutions) {
    }

    public record DistributionItem(String type, String label, long completedExecutions,
                                   long passedExecutions, long failedExecutions) {
    }

    public record ActivityItem(String id, String action, String resourceType, String outcome, String actorName,
                               Instant occurredAt) {
    }
}
