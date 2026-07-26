package io.testhub.platform.dashboard;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProjectDashboardRepository {
    private final JdbcClient jdbc;

    public ProjectDashboardRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public AssetCounts assetCounts(String projectId) {
        return jdbc.sql("""
                SELECT
                    (SELECT COUNT(*) FROM functional_case WHERE project_id = :projectId) AS functional_case_count,
                    (SELECT COUNT(*) FROM api_definition WHERE project_id = :projectId) AS api_definition_count,
                    (SELECT COUNT(*) FROM api_automation_scenario WHERE project_id = :projectId) AS automation_scenario_count,
                    (SELECT COUNT(*) FROM functional_defect
                     WHERE project_id = :projectId AND status NOT IN ('RESOLVED', 'CLOSED')) AS open_defect_count,
                    (SELECT COUNT(*) FROM functional_defect
                     WHERE project_id = :projectId AND status NOT IN ('RESOLVED', 'CLOSED')
                       AND severity = 'CRITICAL') AS critical_defect_count
                """).param("projectId", projectId).query((rs, row) -> new AssetCounts(
                rs.getLong("functional_case_count"), rs.getLong("api_definition_count"),
                rs.getLong("automation_scenario_count"), rs.getLong("open_defect_count"),
                rs.getLong("critical_defect_count"))).single();
    }

    public OutcomeCounts functionalOutcomes(String projectId, Instant from, Instant until) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(run_case.status = 'PASSED'), 0) AS passed_count,
                       COALESCE(SUM(run_case.status IN ('FAILED', 'BLOCKED')), 0) AS failed_count
                FROM functional_test_run_case run_case
                JOIN functional_test_run run ON run.id = run_case.run_id
                WHERE run.project_id = :projectId AND run_case.executed_at >= :from AND run_case.executed_at < :until
                """).param("projectId", projectId).param("from", Timestamp.from(from)).param("until", Timestamp.from(until))
                .query((rs, row) -> outcome(rs.getLong("passed_count"), rs.getLong("failed_count"))).single();
    }

    public OutcomeCounts automationOutcomes(String projectId, Instant from, Instant until) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(status = 'SUCCEEDED'), 0) AS passed_count,
                       COALESCE(SUM(status IN ('FAILED', 'REJECTED', 'TIMED_OUT')), 0) AS failed_count
                FROM api_automation_execution
                WHERE project_id = :projectId AND finished_at >= :from AND finished_at < :until
                """).param("projectId", projectId).param("from", Timestamp.from(from)).param("until", Timestamp.from(until))
                .query((rs, row) -> outcome(rs.getLong("passed_count"), rs.getLong("failed_count"))).single();
    }

    public OutcomeCounts performanceOutcomes(String projectId, Instant from, Instant until) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(status = 'SUCCEEDED'), 0) AS passed_count,
                       COALESCE(SUM(status IN ('FAILED', 'REJECTED', 'TIMED_OUT')), 0) AS failed_count
                FROM performance_test_execution
                WHERE project_id = :projectId AND finished_at >= :from AND finished_at < :until
                """).param("projectId", projectId).param("from", Timestamp.from(from)).param("until", Timestamp.from(until))
                .query((rs, row) -> outcome(rs.getLong("passed_count"), rs.getLong("failed_count"))).single();
    }

    public List<TrendAggregate> functionalTrend(String projectId, Instant from, Instant until) {
        return trend("""
                SELECT DATE(run_case.executed_at) AS day_key,
                       COALESCE(SUM(run_case.status = 'PASSED'), 0) AS passed_count,
                       COALESCE(SUM(run_case.status IN ('FAILED', 'BLOCKED')), 0) AS failed_count
                FROM functional_test_run_case run_case
                JOIN functional_test_run run ON run.id = run_case.run_id
                WHERE run.project_id = :projectId AND run_case.executed_at >= :from AND run_case.executed_at < :until
                GROUP BY DATE(run_case.executed_at)
                """, projectId, from, until);
    }

    public List<TrendAggregate> automationTrend(String projectId, Instant from, Instant until) {
        return trend("""
                SELECT DATE(finished_at) AS day_key,
                       COALESCE(SUM(status = 'SUCCEEDED'), 0) AS passed_count,
                       COALESCE(SUM(status IN ('FAILED', 'REJECTED', 'TIMED_OUT')), 0) AS failed_count
                FROM api_automation_execution
                WHERE project_id = :projectId AND finished_at >= :from AND finished_at < :until
                GROUP BY DATE(finished_at)
                """, projectId, from, until);
    }

    public List<TrendAggregate> performanceTrend(String projectId, Instant from, Instant until) {
        return trend("""
                SELECT DATE(finished_at) AS day_key,
                       COALESCE(SUM(status = 'SUCCEEDED'), 0) AS passed_count,
                       COALESCE(SUM(status IN ('FAILED', 'REJECTED', 'TIMED_OUT')), 0) AS failed_count
                FROM performance_test_execution
                WHERE project_id = :projectId AND finished_at >= :from AND finished_at < :until
                GROUP BY DATE(finished_at)
                """, projectId, from, until);
    }

    public List<ProjectDashboard.ActivityItem> recentActivity(String projectId) {
        return jdbc.sql("""
                SELECT audit.id, audit.action, audit.resource_type, audit.outcome,
                       COALESCE(account.display_name, '系统') AS actor_name, audit.created_at
                FROM audit_log audit
                LEFT JOIN user_account account ON account.id = audit.user_id
                WHERE audit.project_id = :projectId
                ORDER BY audit.created_at DESC, audit.id DESC
                LIMIT 8
                """).param("projectId", projectId).query((rs, row) -> new ProjectDashboard.ActivityItem(
                rs.getString("id"), rs.getString("action"), rs.getString("resource_type"),
                rs.getString("outcome"), rs.getString("actor_name"), rs.getTimestamp("created_at").toInstant())).list();
    }

    public List<ProjectDashboard.TrendPoint> mergeTrend(LocalDate firstDay, LocalDate lastDay,
                                                         List<TrendAggregate>... sources) {
        Map<LocalDate, OutcomeCounts> points = new LinkedHashMap<>();
        for (LocalDate day = firstDay; !day.isAfter(lastDay); day = day.plusDays(1)) {
            points.put(day, new OutcomeCounts(0, 0));
        }
        for (List<TrendAggregate> source : sources) {
            for (TrendAggregate aggregate : source) {
                OutcomeCounts existing = points.get(aggregate.day());
                if (existing == null) continue;
                points.put(aggregate.day(), existing.add(aggregate.outcomes()));
            }
        }
        List<ProjectDashboard.TrendPoint> result = new ArrayList<>();
        points.forEach((day, outcomes) -> result.add(new ProjectDashboard.TrendPoint(day, outcomes.total(),
                outcomes.passed(), outcomes.failed())));
        return result;
    }

    private List<TrendAggregate> trend(String sql, String projectId, Instant from, Instant until) {
        return jdbc.sql(sql).param("projectId", projectId).param("from", Timestamp.from(from))
                .param("until", Timestamp.from(until))
                .query((rs, row) -> new TrendAggregate(rs.getDate("day_key").toLocalDate(),
                        outcome(rs.getLong("passed_count"), rs.getLong("failed_count")))).list();
    }

    private OutcomeCounts outcome(long passed, long failed) {
        return new OutcomeCounts(passed, failed);
    }

    public record AssetCounts(long functionalCaseCount, long apiDefinitionCount, long automationScenarioCount,
                              long openDefectCount, long criticalDefectCount) {
    }

    public record OutcomeCounts(long passed, long failed) {
        public long total() { return passed + failed; }
        public OutcomeCounts add(OutcomeCounts other) { return new OutcomeCounts(passed + other.passed, failed + other.failed); }
    }

    public record TrendAggregate(LocalDate day, OutcomeCounts outcomes) {
    }
}
