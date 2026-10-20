package io.testhub.platform.reporting;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class QualityReportScheduleRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public QualityReportScheduleRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<QualityReportSchedule> findByOrganization(String organizationId) {
        return jdbc.sql(scheduleSelect() + " WHERE organization_id = :organizationId ORDER BY updated_at DESC, name")
                .param("organizationId", organizationId).query((rs, row) -> schedule(rs)).list();
    }

    public List<QualityReportSchedule> findEnabled() {
        return jdbc.sql(scheduleSelect() + " WHERE enabled = TRUE ORDER BY created_at")
                .query((rs, row) -> schedule(rs)).list();
    }

    public Optional<QualityReportSchedule> findById(String id) {
        return jdbc.sql(scheduleSelect() + " WHERE id = :id").param("id", id).query((rs, row) -> schedule(rs)).optional();
    }

    public void insert(String id, String organizationId, String name, List<String> projectIds, String cronExpression,
                       String timezone, int periodDays, boolean enabled, String createdBy) {
        jdbc.sql("""
                INSERT INTO quality_report_schedule
                    (id, organization_id, name, project_ids, cron_expression, timezone, period_days, enabled, created_by)
                VALUES (:id, :organizationId, :name, CAST(:projectIds AS JSON), :cronExpression, :timezone, :periodDays,
                        :enabled, :createdBy)
                """).param("id", id).param("organizationId", organizationId).param("name", name)
                .param("projectIds", write(projectIds)).param("cronExpression", cronExpression).param("timezone", timezone)
                .param("periodDays", periodDays).param("enabled", enabled).param("createdBy", createdBy).update();
    }

    public int update(String id, String organizationId, String name, List<String> projectIds, String cronExpression,
                      String timezone, int periodDays, boolean enabled) {
        return jdbc.sql("""
                UPDATE quality_report_schedule
                SET name = :name, project_ids = CAST(:projectIds AS JSON), cron_expression = :cronExpression,
                    timezone = :timezone, period_days = :periodDays, enabled = :enabled, config_version = config_version + 1,
                    updated_at = CURRENT_TIMESTAMP(3)
                WHERE id = :id AND organization_id = :organizationId
                """).param("id", id).param("organizationId", organizationId).param("name", name)
                .param("projectIds", write(projectIds)).param("cronExpression", cronExpression).param("timezone", timezone)
                .param("periodDays", periodDays).param("enabled", enabled).update();
    }

    public void delete(String id, String organizationId) {
        jdbc.sql("DELETE FROM quality_report_schedule WHERE id = :id AND organization_id = :organizationId")
                .param("id", id).param("organizationId", organizationId).update();
    }

    public void insertRun(String id, QualityReportSchedule schedule, java.time.LocalDate from, java.time.LocalDate to,
                          Instant startedAt) {
        jdbc.sql("""
                INSERT INTO quality_report_schedule_run
                    (id, schedule_id, organization_id, project_ids, range_from, range_to, status, summary, started_at)
                VALUES (:id, :scheduleId, :organizationId, CAST(:projectIds AS JSON), :from, :to, 'RUNNING',
                        CAST(:summary AS JSON), :startedAt)
                """).param("id", id).param("scheduleId", schedule.id()).param("organizationId", schedule.organizationId())
                .param("projectIds", write(schedule.projectIds())).param("from", from).param("to", to)
                .param("summary", write(new QualityReport.Summary(0, 0, 0, 0, 0, 0, 0, 0, null)))
                .param("startedAt", Timestamp.from(startedAt)).update();
    }

    public void completeRun(String id, String status, int deliveryCount, QualityReport.Summary summary,
                            String errorMessage, Instant finishedAt) {
        jdbc.sql("""
                UPDATE quality_report_schedule_run
                SET status = :status, delivery_count = :deliveryCount, summary = CAST(:summary AS JSON),
                    error_message = :errorMessage, finished_at = :finishedAt
                WHERE id = :id
                """).param("id", id).param("status", status).param("deliveryCount", deliveryCount)
                .param("summary", write(summary)).param("errorMessage", errorMessage)
                .param("finishedAt", Timestamp.from(finishedAt)).update();
    }

    public void markScheduleRun(QualityReportSchedule schedule, String runId, String status, Instant at) {
        jdbc.sql("""
                UPDATE quality_report_schedule
                SET last_run_id = :runId, last_run_status = :status, last_run_at = :at, updated_at = CURRENT_TIMESTAMP(3)
                WHERE id = :id
                """).param("id", schedule.id()).param("runId", runId).param("status", status).param("at", Timestamp.from(at)).update();
    }

    public List<QualityReportScheduleRun> findRuns(String scheduleId, int limit) {
        return jdbc.sql("""
                SELECT id, schedule_id, organization_id, project_ids, range_from, range_to, status, delivery_count,
                       summary, error_message, started_at, finished_at
                FROM quality_report_schedule_run WHERE schedule_id = :scheduleId
                ORDER BY started_at DESC, id DESC LIMIT :limit
                """).param("scheduleId", scheduleId).param("limit", limit).query((rs, row) -> new QualityReportScheduleRun(
                rs.getString("id"), rs.getString("schedule_id"), rs.getString("organization_id"), projectIds(rs.getString("project_ids")),
                rs.getDate("range_from").toLocalDate(), rs.getDate("range_to").toLocalDate(), rs.getString("status"),
                rs.getInt("delivery_count"), readSummary(rs.getString("summary")), rs.getString("error_message"),
                instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("finished_at")))).list();
    }

    private String scheduleSelect() {
        return "SELECT id, organization_id, name, project_ids, cron_expression, timezone, period_days, enabled, last_run_id, "
                + "last_run_status, last_run_at, created_by, created_at, updated_at FROM quality_report_schedule";
    }

    private QualityReportSchedule schedule(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new QualityReportSchedule(rs.getString("id"), rs.getString("organization_id"), rs.getString("name"),
                projectIds(rs.getString("project_ids")), rs.getString("cron_expression"), rs.getString("timezone"),
                rs.getInt("period_days"), rs.getBoolean("enabled"), rs.getString("last_run_id"),
                rs.getString("last_run_status"), instant(rs.getTimestamp("last_run_at")), rs.getString("created_by"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
    }

    private List<String> projectIds(String value) {
        try {
            return List.copyOf(json.readValue(value, new TypeReference<List<String>>() { }));
        } catch (Exception exception) {
            throw new IllegalStateException("质量报告计划项目范围损坏", exception);
        }
    }

    private QualityReport.Summary readSummary(String value) {
        try {
            return json.readValue(value, QualityReport.Summary.class);
        } catch (Exception exception) {
            throw new IllegalStateException("质量报告计划运行摘要损坏", exception);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("无法保存质量报告计划数据", exception);
        }
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
