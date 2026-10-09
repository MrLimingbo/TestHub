package io.testhub.platform.performance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PerformanceTestPlanRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public PerformanceTestPlanRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<PerformanceTestPlan> findByProject(String projectId) {
        return jdbc.sql("""
                SELECT plan.id, plan.organization_id, plan.project_id, plan.name, plan.description, plan.status,
                       plan.version, COUNT(thread_group.id) AS thread_group_count, plan.created_by,
                       plan.created_at, plan.updated_at
                FROM performance_test_plan plan
                LEFT JOIN performance_test_thread_group thread_group ON thread_group.plan_id = plan.id
                WHERE plan.project_id = :projectId
                GROUP BY plan.id, plan.organization_id, plan.project_id, plan.name, plan.description, plan.status,
                         plan.version, plan.created_by, plan.created_at, plan.updated_at
                ORDER BY plan.updated_at DESC, plan.id DESC
                """).param("projectId", projectId).query((rs, row) -> new PerformanceTestPlan(
                rs.getString("id"), rs.getString("organization_id"), rs.getString("project_id"), rs.getString("name"),
                rs.getString("description"), rs.getString("status"), rs.getInt("version"),
                rs.getInt("thread_group_count"), rs.getString("created_by"), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")))).list();
    }

    public Optional<PerformanceTestPlanDetail> findDetail(String projectId, String planId) {
        return jdbc.sql("""
                SELECT id, organization_id, project_id, name, description, status, version, created_by, created_at, updated_at
                FROM performance_test_plan WHERE project_id = :projectId AND id = :planId
                """).param("projectId", projectId).param("planId", planId).query((rs, row) ->
                new PerformanceTestPlanDetail(rs.getString("id"), rs.getString("organization_id"),
                        rs.getString("project_id"), rs.getString("name"), rs.getString("description"),
                        rs.getString("status"), rs.getInt("version"), rs.getString("created_by"),
                        instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")),
                        findThreadGroups(planId))).optional();
    }

    public int countByProject(String projectId) {
        Integer result = jdbc.sql("SELECT COUNT(*) FROM performance_test_plan WHERE project_id = :projectId")
                .param("projectId", projectId).query(Integer.class).single();
        return result == null ? 0 : result;
    }

    public void lockProject(String projectId) {
        jdbc.sql("SELECT id FROM project WHERE id=:project FOR UPDATE").param("project", projectId).query(String.class).single();
    }

    public void insertPlan(String id, String organizationId, String projectId, String name, String description,
                           String status, String createdBy) {
        jdbc.sql("""
                INSERT INTO performance_test_plan
                    (id, organization_id, project_id, name, description, status, version, created_by)
                VALUES (:id, :organizationId, :projectId, :name, :description, :status, 1, :createdBy)
                """).param("id", id).param("organizationId", organizationId).param("projectId", projectId)
                .param("name", name).param("description", description).param("status", status)
                .param("createdBy", createdBy).update();
    }

    public void insertThreadGroup(String id, String planId, String organizationId, String projectId,
                                  PerformanceTestPlanService.ThreadGroupPayload group, int sortOrder) {
        jdbc.sql("""
                INSERT INTO performance_test_thread_group
                    (id, plan_id, organization_id, project_id, name, target_url, load_model, thread_count,
                     ramp_up_seconds, step_count, step_interval_seconds, duration_seconds, target_tps, p95_threshold_millis, error_threshold_percent,
                     request_method, request_headers, query_parameters, request_body, expected_status_code, response_contains, data_set_csv,
                     rendezvous_users, sort_order)
                VALUES (:id, :planId, :organizationId, :projectId, :name, :targetUrl, :loadModel, :threadCount,
                        :rampUpSeconds, :stepCount, :stepIntervalSeconds, :durationSeconds, :targetTps, :p95ThresholdMillis, :errorThresholdPercent,
                        :requestMethod, :requestHeaders, :queryParameters, :requestBody, :expectedStatusCode, :responseContains, :dataSetCsv,
                        :rendezvousUsers, :sortOrder)
                """).param("id", id).param("planId", planId).param("organizationId", organizationId)
                .param("projectId", projectId).param("name", group.name()).param("targetUrl", group.targetUrl())
                .param("loadModel", group.loadModel()).param("threadCount", group.threadCount())
                .param("rampUpSeconds", group.rampUpSeconds()).param("durationSeconds", group.durationSeconds())
                .param("stepCount", group.stepCount()).param("stepIntervalSeconds", group.stepIntervalSeconds())
                .param("targetTps", group.targetTps()).param("p95ThresholdMillis", group.p95ThresholdMillis())
                .param("errorThresholdPercent", group.errorThresholdPercent()).param("requestMethod", group.requestMethod())
                .param("requestHeaders", json(group.requestHeaders())).param("requestBody", group.requestBody())
                .param("queryParameters", json(group.queryParameters()))
                .param("expectedStatusCode", group.expectedStatusCode()).param("responseContains", group.responseContains())
                .param("dataSetCsv", group.dataSetCsv())
                .param("rendezvousUsers", group.rendezvousUsers())
                .param("sortOrder", sortOrder).update();
    }

    public int updatePlan(String projectId, String planId, int expectedVersion, String name, String description,
                          String status) {
        return jdbc.sql("""
                UPDATE performance_test_plan
                SET name = :name, description = :description, status = :status, version = version + 1,
                    updated_at = CURRENT_TIMESTAMP(3)
                WHERE project_id = :projectId AND id = :planId AND version = :expectedVersion
                """).param("projectId", projectId).param("planId", planId).param("expectedVersion", expectedVersion)
                .param("name", name).param("description", description).param("status", status).update();
    }

    public int deleteThreadGroups(String projectId, String planId) {
        return jdbc.sql("DELETE FROM performance_test_thread_group WHERE project_id = :projectId AND plan_id = :planId")
                .param("projectId", projectId).param("planId", planId).update();
    }

    public int deletePlan(String projectId, String planId) {
        return jdbc.sql("DELETE FROM performance_test_plan WHERE project_id = :projectId AND id = :planId")
                .param("projectId", projectId).param("planId", planId).update();
    }

    private List<PerformanceTestThreadGroup> findThreadGroups(String planId) {
        return jdbc.sql("""
                SELECT id, plan_id, organization_id, project_id, name, target_url, load_model, thread_count,
                       ramp_up_seconds, step_count, step_interval_seconds, duration_seconds, target_tps, p95_threshold_millis, error_threshold_percent,
                       request_method, request_headers, query_parameters, request_body, expected_status_code, response_contains,
                       data_set_csv, rendezvous_users,
                       sort_order, created_at, updated_at
                FROM performance_test_thread_group WHERE plan_id = :planId
                ORDER BY sort_order, id
                """).param("planId", planId).query((rs, row) -> new PerformanceTestThreadGroup(
                rs.getString("id"), rs.getString("plan_id"), rs.getString("organization_id"),
                rs.getString("project_id"), rs.getString("name"), rs.getString("target_url"),
                rs.getString("load_model"), rs.getInt("thread_count"), rs.getInt("ramp_up_seconds"),
                nullableInteger(rs, "step_count"), nullableInteger(rs, "step_interval_seconds"), rs.getInt("duration_seconds"), nullableInteger(rs, "target_tps"),
                nullableInteger(rs, "p95_threshold_millis"), rs.getBigDecimal("error_threshold_percent"),
                rs.getString("request_method"), requestHeaders(rs.getString("request_headers")),
                queryParameters(rs.getString("query_parameters")), rs.getString("request_body"),
                nullableInteger(rs, "expected_status_code"), rs.getString("response_contains"),
                rs.getString("data_set_csv"),
                nullableInteger(rs, "rendezvous_users"),
                rs.getInt("sort_order"), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")))).list();
    }

    private Map<String, String> requestHeaders(String source) {
        return parameters(source, "已保存的压测请求头格式不合法");
    }

    private Map<String, String> queryParameters(String source) {
        return parameters(source, "已保存的压测查询参数格式不合法");
    }

    private Map<String, String> parameters(String source, String message) {
        try {
            Map<String, String> values = json.readValue(source == null || source.isBlank() ? "{}" : source, new TypeReference<>() { });
            return Map.copyOf(new LinkedHashMap<>(values));
        } catch (Exception exception) {
            throw new IllegalStateException(message, exception);
        }
    }

    private String json(Object source) {
        try {
            return json.writeValueAsString(source);
        } catch (Exception exception) {
            throw new IllegalStateException("压测请求参数无法序列化", exception);
        }
    }

    private Integer nullableInteger(java.sql.ResultSet resultSet, String column) throws java.sql.SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
