package io.testhub.platform.reporting;

import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ProjectSummary;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.dashboard.ProjectDashboard;
import io.testhub.platform.dashboard.ProjectDashboardRepository;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class QualityReportService {
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_RANGE_DAYS = 90;
    private static final int MAX_PROJECTS = 50;
    private final AccessService access;
    private final ProjectDashboardRepository dashboard;
    private final AuditService audit;

    public QualityReportService(AccessService access, ProjectDashboardRepository dashboard, AuditService audit) {
        this.access = access;
        this.dashboard = dashboard;
        this.audit = audit;
    }

    public QualityReport get(AuthenticatedPrincipal principal, List<String> requestedProjectIds,
                             LocalDate requestedFrom, LocalDate requestedTo) {
        return load(principal, requestedProjectIds, requestedFrom, requestedTo).report();
    }

    public CsvExport export(AuthenticatedPrincipal principal, List<String> requestedProjectIds,
                            LocalDate requestedFrom, LocalDate requestedTo) {
        LoadedReport loaded = load(principal, requestedProjectIds, requestedFrom, requestedTo);
        byte[] content = csv(loaded.report()).getBytes(StandardCharsets.UTF_8);
        for (ProjectSummary project : loaded.projects()) {
            audit.record(principal, project.organizationId(), project.id(), "QUALITY_REPORT_EXPORTED", "QUALITY_REPORT",
                    project.id(), Map.of("format", "CSV", "from", loaded.report().from().toString(),
                            "to", loaded.report().to().toString(), "sizeBytes", content.length));
        }
        return new CsvExport("testhub-quality-report-" + loaded.report().from() + "-to-" + loaded.report().to() + ".csv", content);
    }

    private LoadedReport load(AuthenticatedPrincipal principal, List<String> requestedProjectIds,
                              LocalDate requestedFrom, LocalDate requestedTo) {
        DateRange range = dateRange(requestedFrom, requestedTo);
        List<ProjectSummary> projects = resolveProjects(principal, requestedProjectIds);
        return new LoadedReport(projects, build(projects, range));
    }

    private List<ProjectSummary> resolveProjects(AuthenticatedPrincipal principal, List<String> requestedProjectIds) {
        Map<String, ProjectSummary> accessible = new LinkedHashMap<>();
        AccessService.AccessContext context = access.context(principal);
        context.organizations().forEach(organization -> context.projects()
                .getOrDefault(organization.id(), List.of())
                .forEach(project -> accessible.putIfAbsent(project.id(), project)));
        if (accessible.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "没有可访问的项目");

        List<String> requested = normalizeProjectIds(requestedProjectIds);
        if (requested.isEmpty()) return List.copyOf(accessible.values());
        if (requested.size() > MAX_PROJECTS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单次最多选择 50 个项目");
        }
        List<ProjectSummary> selected = new ArrayList<>();
        for (String projectId : requested) {
            if (!accessible.containsKey(projectId)) {
                access.requireProjectRead(principal, projectId);
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "没有项目访问权限");
            }
            selected.add(access.requireProjectRead(principal, projectId));
        }
        return selected;
    }

    private List<String> normalizeProjectIds(List<String> rawProjectIds) {
        if (rawProjectIds == null || rawProjectIds.isEmpty()) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String raw : rawProjectIds) {
            if (raw == null || raw.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "项目筛选参数不能为空");
            }
            Arrays.stream(raw.split(",")).map(String::trim).forEach(projectId -> {
                if (projectId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "项目筛选参数不能为空");
                result.add(projectId);
            });
        }
        return List.copyOf(result);
    }

    private DateRange dateRange(LocalDate requestedFrom, LocalDate requestedTo) {
        LocalDate today = Instant.now().atZone(DISPLAY_ZONE).toLocalDate();
        LocalDate to = requestedTo == null ? today : requestedTo;
        LocalDate from = requestedFrom == null ? to.minusDays(29) : requestedFrom;
        if (from.isAfter(to)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "开始日期不能晚于结束日期");
        long days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        if (days < 1 || days > MAX_RANGE_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "统计范围应在 1 到 90 天之间");
        }
        return new DateRange(from, to, from.atStartOfDay(DISPLAY_ZONE).toInstant(),
                to.plusDays(1).atStartOfDay(DISPLAY_ZONE).toInstant());
    }

    private QualityReport build(List<ProjectSummary> projects, DateRange range) {
        Instant generatedAt = Instant.now();
        long functionalCaseCount = 0;
        long apiDefinitionCount = 0;
        long automationScenarioCount = 0;
        long openDefectCount = 0;
        long criticalDefectCount = 0;
        ProjectDashboardRepository.OutcomeCounts total = new ProjectDashboardRepository.OutcomeCounts(0, 0);
        List<ProjectDashboardRepository.TrendAggregate> functionalTrend = new ArrayList<>();
        List<ProjectDashboardRepository.TrendAggregate> automationTrend = new ArrayList<>();
        List<ProjectDashboardRepository.TrendAggregate> performanceTrend = new ArrayList<>();
        List<QualityReport.ProjectHealth> health = new ArrayList<>();

        for (ProjectSummary project : projects) {
            ProjectDashboardRepository.AssetCounts assets = dashboard.assetCounts(project.id());
            ProjectDashboardRepository.OutcomeCounts functional = dashboard.functionalOutcomes(project.id(), range.fromInstant(), range.untilInstant());
            ProjectDashboardRepository.OutcomeCounts automation = dashboard.automationOutcomes(project.id(), range.fromInstant(), range.untilInstant());
            ProjectDashboardRepository.OutcomeCounts performance = dashboard.performanceOutcomes(project.id(), range.fromInstant(), range.untilInstant());
            ProjectDashboardRepository.OutcomeCounts outcomes = functional.add(automation).add(performance);
            functionalCaseCount += assets.functionalCaseCount();
            apiDefinitionCount += assets.apiDefinitionCount();
            automationScenarioCount += assets.automationScenarioCount();
            openDefectCount += assets.openDefectCount();
            criticalDefectCount += assets.criticalDefectCount();
            total = total.add(outcomes);
            functionalTrend.addAll(dashboard.functionalTrend(project.id(), range.fromInstant(), range.untilInstant()));
            automationTrend.addAll(dashboard.automationTrend(project.id(), range.fromInstant(), range.untilInstant()));
            performanceTrend.addAll(dashboard.performanceTrend(project.id(), range.fromInstant(), range.untilInstant()));
            health.add(new QualityReport.ProjectHealth(project.id(), project.name(), project.code(), outcomes.total(), outcomes.passed(),
                    outcomes.failed(), passRate(outcomes), assets.openDefectCount(), assets.criticalDefectCount(),
                    healthStatus(outcomes, assets.criticalDefectCount())));
        }

        QualityReport.Summary summary = new QualityReport.Summary(functionalCaseCount, apiDefinitionCount,
                automationScenarioCount, openDefectCount, criticalDefectCount, total.total(), total.passed(), total.failed(),
                passRate(total));
        List<ProjectDashboard.TrendPoint> trend = dashboard.mergeTrend(range.from(), range.to(), functionalTrend,
                automationTrend, performanceTrend);
        return new QualityReport(range.from(), range.to(), generatedAt, projects.size(), summary, trend, health);
    }

    private Integer passRate(ProjectDashboardRepository.OutcomeCounts outcomes) {
        return outcomes.total() == 0 ? null : (int) Math.round(outcomes.passed() * 100.0 / outcomes.total());
    }

    private String healthStatus(ProjectDashboardRepository.OutcomeCounts outcomes, long criticalDefectCount) {
        if (criticalDefectCount > 0) return "AT_RISK";
        if (outcomes.total() == 0) return "NO_EXECUTIONS";
        return outcomes.failed() == 0 ? "HEALTHY" : "ATTENTION";
    }

    private String csv(QualityReport report) {
        StringBuilder result = new StringBuilder("\uFEFF");
        csvRow(result, "段落", "项目", "项目编码", "统计周期", "项目数", "功能用例", "接口定义", "自动化场景",
                "完成执行", "通过执行", "失败执行", "通过率(%)", "未关闭缺陷", "严重缺陷", "健康状态");
        QualityReport.Summary summary = report.summary();
        csvRow(result, "报表摘要", "全部项目", "", report.from() + " 至 " + report.to(), String.valueOf(report.projectCount()),
                String.valueOf(summary.functionalCaseCount()), String.valueOf(summary.apiDefinitionCount()),
                String.valueOf(summary.automationScenarioCount()), String.valueOf(summary.completedExecutions()),
                String.valueOf(summary.passedExecutions()), String.valueOf(summary.failedExecutions()),
                nullableNumber(summary.passRatePercent()), String.valueOf(summary.openDefectCount()),
                String.valueOf(summary.criticalDefectCount()), qualityLabel(summary.criticalDefectCount(), summary.completedExecutions(),
                        summary.failedExecutions()));
        for (ProjectDashboard.TrendPoint point : report.trend()) {
            csvRow(result, "每日趋势", point.date().toString(), "", "", "", "", "", "",
                    String.valueOf(point.completedExecutions()), String.valueOf(point.passedExecutions()),
                    String.valueOf(point.failedExecutions()), nullableNumber(passRate(point.passedExecutions(), point.completedExecutions())),
                    "", "", "");
        }
        for (QualityReport.ProjectHealth project : report.projects()) {
            csvRow(result, "项目健康度", project.projectName(), project.projectCode(), report.from() + " 至 " + report.to(), "", "", "", "",
                    String.valueOf(project.completedExecutions()), String.valueOf(project.passedExecutions()),
                    String.valueOf(project.failedExecutions()), nullableNumber(project.passRatePercent()),
                    String.valueOf(project.openDefectCount()), String.valueOf(project.criticalDefectCount()),
                    healthLabel(project.healthStatus()));
        }
        return result.toString();
    }

    private void csvRow(StringBuilder target, String... values) {
        for (int index = 0; index < values.length; index++) {
            if (index > 0) target.append(',');
            target.append(csvCell(values[index]));
        }
        target.append("\r\n");
    }

    private String csvCell(String value) {
        String cell = Objects.requireNonNullElse(value, "");
        String leading = cell.stripLeading();
        if (!leading.isEmpty() && "=+-@".indexOf(leading.charAt(0)) >= 0) cell = "'" + cell;
        return '"' + cell.replace("\"", "\"\"") + '"';
    }

    private Integer passRate(long passed, long total) {
        return total == 0 ? null : (int) Math.round(passed * 100.0 / total);
    }

    private String nullableNumber(Integer value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String qualityLabel(long criticalDefects, long completed, long failed) {
        if (criticalDefects > 0) return "有风险";
        if (completed == 0) return "无执行";
        return failed == 0 ? "健康" : "需关注";
    }

    private String healthLabel(String status) {
        return switch (status) {
            case "AT_RISK" -> "有风险";
            case "ATTENTION" -> "需关注";
            case "HEALTHY" -> "健康";
            default -> "无执行";
        };
    }

    public record CsvExport(String filename, byte[] content) {
    }

    private record DateRange(LocalDate from, LocalDate to, Instant fromInstant, Instant untilInstant) {
    }

    private record LoadedReport(List<ProjectSummary> projects, QualityReport report) {
    }
}
