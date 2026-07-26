package io.testhub.platform.uiautomation;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UiAutomationScheduleRepository {
    private final JdbcClient jdbc;

    public UiAutomationScheduleRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public List<UiAutomationSchedule> findByProject(String projectId) {
        return jdbc.sql(select() + " WHERE s.project_id = :projectId ORDER BY s.updated_at DESC, s.name")
                .param("projectId", projectId).query((rs, row) -> schedule(rs)).list();
    }

    public Optional<UiAutomationSchedule> findById(String id) {
        return jdbc.sql(select() + " WHERE s.id = :id").param("id", id).query((rs, row) -> schedule(rs)).optional();
    }

    public List<UiAutomationSchedule> findEnabled() {
        return jdbc.sql(select() + " WHERE s.enabled = TRUE ORDER BY s.created_at").query((rs, row) -> schedule(rs)).list();
    }

    public void insert(String id, String organizationId, String projectId, ScheduleInput input, String userId) {
        jdbc.sql("""
                INSERT INTO ui_automation_schedule
                    (id, organization_id, project_id, scenario_id, functional_plan_id, name, cron_expression, timezone,
                     timeout_seconds, max_retry_attempts, retry_backoff_seconds, enabled, created_by, updated_by)
                VALUES (:id, :organizationId, :projectId, :scenarioId, :functionalPlanId, :name, :cronExpression, :timezone,
                        :timeoutSeconds, :maxRetryAttempts, :retryBackoffSeconds, :enabled, :userId, :userId)
                """).param("id", id).param("organizationId", organizationId).param("projectId", projectId)
                .param("scenarioId", input.scenarioId()).param("functionalPlanId", input.functionalPlanId())
                .param("name", input.name()).param("cronExpression", input.cronExpression()).param("timezone", input.timezone())
                .param("timeoutSeconds", input.timeoutSeconds()).param("maxRetryAttempts", input.maxRetryAttempts())
                .param("retryBackoffSeconds", input.retryBackoffSeconds()).param("enabled", input.enabled())
                .param("userId", userId).update();
    }

    public int update(String projectId, String id, ScheduleInput input, String userId) {
        return jdbc.sql("""
                UPDATE ui_automation_schedule
                SET scenario_id = :scenarioId, functional_plan_id = :functionalPlanId, name = :name,
                    cron_expression = :cronExpression, timezone = :timezone, timeout_seconds = :timeoutSeconds,
                    max_retry_attempts = :maxRetryAttempts, retry_backoff_seconds = :retryBackoffSeconds,
                    enabled = :enabled, updated_by = :userId, updated_at = CURRENT_TIMESTAMP(3)
                WHERE project_id = :projectId AND id = :id
                """).param("projectId", projectId).param("id", id).param("scenarioId", input.scenarioId())
                .param("functionalPlanId", input.functionalPlanId()).param("name", input.name())
                .param("cronExpression", input.cronExpression()).param("timezone", input.timezone())
                .param("timeoutSeconds", input.timeoutSeconds()).param("maxRetryAttempts", input.maxRetryAttempts())
                .param("retryBackoffSeconds", input.retryBackoffSeconds()).param("enabled", input.enabled())
                .param("userId", userId).update();
    }

    public void delete(String projectId, String id) {
        jdbc.sql("DELETE FROM ui_automation_schedule WHERE project_id = :projectId AND id = :id")
                .param("projectId", projectId).param("id", id).update();
    }

    public void markTriggered(String id, String executionId, Instant requestedAt) {
        jdbc.sql("""
                UPDATE ui_automation_schedule
                SET last_execution_id = :executionId, last_execution_status = 'QUEUED', last_triggered_at = :requestedAt,
                    updated_at = CURRENT_TIMESTAMP(3)
                WHERE id = :id
                """).param("id", id).param("executionId", executionId).param("requestedAt", Timestamp.from(requestedAt)).update();
    }

    public void markOutcome(String id, String executionId, String status) {
        jdbc.sql("""
                UPDATE ui_automation_schedule SET last_execution_status = :status, updated_at = CURRENT_TIMESTAMP(3)
                WHERE id = :id AND last_execution_id = :executionId
                """).param("id", id).param("executionId", executionId).param("status", status).update();
    }

    private String select() {
        return "SELECT s.id, s.organization_id, s.project_id, s.scenario_id, s.functional_plan_id, p.name AS functional_plan_name, "
                + "s.name, s.cron_expression, s.timezone, s.timeout_seconds, s.max_retry_attempts, s.retry_backoff_seconds, "
                + "s.enabled, s.last_execution_id, s.last_execution_status, s.last_triggered_at, s.created_by, s.created_at, s.updated_at "
                + "FROM ui_automation_schedule s LEFT JOIN functional_test_plan p ON p.id = s.functional_plan_id";
    }

    private UiAutomationSchedule schedule(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new UiAutomationSchedule(rs.getString("id"), rs.getString("organization_id"), rs.getString("project_id"),
                rs.getString("scenario_id"), rs.getString("functional_plan_id"), rs.getString("functional_plan_name"),
                rs.getString("name"), rs.getString("cron_expression"), rs.getString("timezone"), rs.getInt("timeout_seconds"),
                rs.getInt("max_retry_attempts"), rs.getInt("retry_backoff_seconds"), rs.getBoolean("enabled"),
                rs.getString("last_execution_id"), rs.getString("last_execution_status"), instant(rs.getTimestamp("last_triggered_at")),
                rs.getString("created_by"), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
    }

    private Instant instant(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant(); }

    public record ScheduleInput(String scenarioId, String functionalPlanId, String name, String cronExpression, String timezone,
                                int timeoutSeconds, int maxRetryAttempts, int retryBackoffSeconds, boolean enabled) { }
}
