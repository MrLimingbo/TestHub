package io.testhub.platform.apiautomation;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ApiAutomationScheduleRepository {
    private final JdbcClient jdbc;

    public ApiAutomationScheduleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ApiAutomationSchedule> findByProject(String projectId) {
        return jdbc.sql(select() + " WHERE project_id = :projectId ORDER BY updated_at DESC, id DESC")
                .param("projectId", projectId).query((rs, row) -> schedule(rs)).list();
    }

    public List<ApiAutomationSchedule> findEnabled() {
        return jdbc.sql(select() + " WHERE enabled = TRUE ORDER BY id")
                .query((rs, row) -> schedule(rs)).list();
    }

    public Optional<ApiAutomationSchedule> findById(String id) {
        return jdbc.sql(select() + " WHERE id = :id").param("id", id).query((rs, row) -> schedule(rs)).optional();
    }

    public void insert(String id, String organizationId, String projectId, String scenarioId, String name,
                       String cronExpression, String timezone, int timeoutSeconds, int maxConsecutiveFailures,
                       boolean enabled, String createdBy) {
        jdbc.sql("""
                INSERT INTO api_automation_schedule
                    (id, organization_id, project_id, scenario_id, name, cron_expression, timezone, timeout_seconds,
                     max_consecutive_failures, enabled, created_by)
                VALUES (:id, :organizationId, :projectId, :scenarioId, :name, :cronExpression, :timezone,
                        :timeoutSeconds, :maxConsecutiveFailures, :enabled, :createdBy)
                """).param("id", id).param("organizationId", organizationId).param("projectId", projectId)
                .param("scenarioId", scenarioId).param("name", name).param("cronExpression", cronExpression)
                .param("timezone", timezone).param("timeoutSeconds", timeoutSeconds)
                .param("maxConsecutiveFailures", maxConsecutiveFailures).param("enabled", enabled).param("createdBy", createdBy).update();
    }

    public int update(String projectId, String id, String scenarioId, String name, String cronExpression, String timezone,
                      int timeoutSeconds, int maxConsecutiveFailures, boolean enabled) {
        return jdbc.sql("""
                UPDATE api_automation_schedule
                SET scenario_id = :scenarioId, name = :name, cron_expression = :cronExpression, timezone = :timezone,
                    timeout_seconds = :timeoutSeconds, max_consecutive_failures = :maxConsecutiveFailures,
                    enabled = :enabled, consecutive_failures = 0, last_execution_status = NULL,
                    updated_at = CURRENT_TIMESTAMP(3)
                WHERE id = :id AND project_id = :projectId
                """).param("id", id).param("projectId", projectId).param("scenarioId", scenarioId).param("name", name)
                .param("cronExpression", cronExpression).param("timezone", timezone).param("timeoutSeconds", timeoutSeconds)
                .param("maxConsecutiveFailures", maxConsecutiveFailures).param("enabled", enabled).update();
    }

    public void delete(String projectId, String id) {
        jdbc.sql("DELETE FROM api_automation_schedule WHERE id = :id AND project_id = :projectId")
                .param("id", id).param("projectId", projectId).update();
    }

    public void markTriggered(String id, String executionId, Instant triggeredAt) {
        jdbc.sql("""
                UPDATE api_automation_schedule
                SET last_execution_id = :executionId, last_execution_status = 'QUEUED', last_triggered_at = :triggeredAt,
                    updated_at = CURRENT_TIMESTAMP(3)
                WHERE id = :id AND enabled = TRUE
                """).param("id", id).param("executionId", executionId).param("triggeredAt", Timestamp.from(triggeredAt)).update();
    }

    public List<ScheduleOutcome> findTerminalOutcomesToReconcile() {
        return jdbc.sql("""
                SELECT s.id, s.organization_id, s.project_id, s.last_execution_id, s.last_execution_status,
                       s.consecutive_failures, s.max_consecutive_failures, e.status AS execution_status
                FROM api_automation_schedule s
                JOIN api_automation_execution e ON e.id = s.last_execution_id AND e.project_id = s.project_id
                WHERE s.last_execution_id IS NOT NULL
                  AND e.status IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'REJECTED', 'TIMED_OUT')
                  AND (s.last_execution_status IS NULL OR s.last_execution_status <> e.status)
                """).query((rs, row) -> new ScheduleOutcome(rs.getString("id"), rs.getString("organization_id"),
                rs.getString("project_id"), rs.getString("last_execution_id"), rs.getString("last_execution_status"),
                rs.getInt("consecutive_failures"), rs.getInt("max_consecutive_failures"), rs.getString("execution_status"))).list();
    }

    public void applyOutcome(String id, String status, int consecutiveFailures, boolean enabled) {
        jdbc.sql("""
                UPDATE api_automation_schedule
                SET last_execution_status = :status, consecutive_failures = :consecutiveFailures, enabled = :enabled,
                    updated_at = CURRENT_TIMESTAMP(3)
                WHERE id = :id
                """).param("id", id).param("status", status).param("consecutiveFailures", consecutiveFailures)
                .param("enabled", enabled).update();
    }

    private String select() {
        return """
                SELECT id, organization_id, project_id, scenario_id, name, cron_expression, timezone, timeout_seconds,
                       max_consecutive_failures, consecutive_failures, enabled, last_execution_id, last_execution_status,
                       last_triggered_at, created_by, created_at, updated_at
                FROM api_automation_schedule
                """;
    }

    private ApiAutomationSchedule schedule(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ApiAutomationSchedule(rs.getString("id"), rs.getString("organization_id"), rs.getString("project_id"),
                rs.getString("scenario_id"), rs.getString("name"), rs.getString("cron_expression"), rs.getString("timezone"),
                rs.getInt("timeout_seconds"), rs.getInt("max_consecutive_failures"), rs.getInt("consecutive_failures"),
                rs.getBoolean("enabled"), rs.getString("last_execution_id"), rs.getString("last_execution_status"),
                instant(rs.getTimestamp("last_triggered_at")), rs.getString("created_by"), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")));
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record ScheduleOutcome(String scheduleId, String organizationId, String projectId, String executionId,
                                  String previousStatus, int consecutiveFailures, int maxConsecutiveFailures,
                                  String executionStatus) {
    }
}
