package io.testhub.platform.dashboard;

import io.testhub.platform.access.AccessService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ProjectDashboardService {
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");
    private final AccessService access;
    private final ProjectDashboardRepository dashboard;

    public ProjectDashboardService(AccessService access, ProjectDashboardRepository dashboard) {
        this.access = access;
        this.dashboard = dashboard;
    }

    public ProjectDashboard get(AuthenticatedPrincipal principal, String projectId, int periodDays) {
        access.requireProjectRead(principal, projectId);
        Instant generatedAt = Instant.now();
        LocalDate lastDay = generatedAt.atZone(DISPLAY_ZONE).toLocalDate();
        LocalDate firstDay = lastDay.minusDays(periodDays - 1L);
        Instant from = firstDay.atStartOfDay(DISPLAY_ZONE).toInstant();
        Instant until = lastDay.plusDays(1).atStartOfDay(DISPLAY_ZONE).toInstant();
        ProjectDashboardRepository.AssetCounts assets = dashboard.assetCounts(projectId);
        ProjectDashboardRepository.OutcomeCounts functional = dashboard.functionalOutcomes(projectId, from, until);
        ProjectDashboardRepository.OutcomeCounts automation = dashboard.automationOutcomes(projectId, from, until);
        ProjectDashboardRepository.OutcomeCounts performance = dashboard.performanceOutcomes(projectId, from, until);
        ProjectDashboardRepository.OutcomeCounts total = functional.add(automation).add(performance);
        Integer passRate = total.total() == 0 ? null : (int) Math.round(total.passed() * 100.0 / total.total());
        String healthStatus = healthStatus(total, assets.criticalDefectCount());
        ProjectDashboard.Summary summary = new ProjectDashboard.Summary(assets.functionalCaseCount(),
                assets.apiDefinitionCount(), assets.automationScenarioCount(), assets.openDefectCount(),
                assets.criticalDefectCount(), total.total(), total.passed(), total.failed(), passRate, healthStatus);
        List<ProjectDashboard.TrendPoint> trend = dashboard.mergeTrend(firstDay, lastDay,
                dashboard.functionalTrend(projectId, from, until), dashboard.automationTrend(projectId, from, until),
                dashboard.performanceTrend(projectId, from, until));
        List<ProjectDashboard.DistributionItem> distribution = List.of(
                distribution("FUNCTIONAL", "功能测试", functional),
                distribution("API_AUTOMATION", "接口自动化", automation),
                distribution("PERFORMANCE", "性能测试", performance));
        return new ProjectDashboard(periodDays, generatedAt, summary, trend, distribution,
                dashboard.recentActivity(projectId));
    }

    private ProjectDashboard.DistributionItem distribution(String type, String label,
                                                           ProjectDashboardRepository.OutcomeCounts outcomes) {
        return new ProjectDashboard.DistributionItem(type, label, outcomes.total(), outcomes.passed(), outcomes.failed());
    }

    private String healthStatus(ProjectDashboardRepository.OutcomeCounts outcomes, long criticalDefectCount) {
        if (criticalDefectCount > 0) return "AT_RISK";
        if (outcomes.total() == 0) return "NO_EXECUTIONS";
        return outcomes.failed() == 0 ? "HEALTHY" : "ATTENTION";
    }
}
