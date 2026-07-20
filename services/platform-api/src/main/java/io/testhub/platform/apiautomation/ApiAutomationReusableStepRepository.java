package io.testhub.platform.apiautomation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ApiAutomationReusableStepRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public ApiAutomationReusableStepRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<ApiAutomationReusableStep> findByProject(String projectId) {
        return jdbc.sql("""
                SELECT id, organization_id, project_id, name, description, version, JSON_LENGTH(steps) AS step_count,
                       created_by, created_at, updated_at
                FROM api_automation_reusable_step WHERE project_id = :projectId
                ORDER BY updated_at DESC, id DESC
                """).param("projectId", projectId).query((rs, row) -> new ApiAutomationReusableStep(
                rs.getString("id"), rs.getString("organization_id"), rs.getString("project_id"), rs.getString("name"),
                rs.getString("description"), rs.getInt("version"), rs.getInt("step_count"), rs.getString("created_by"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")))).list();
    }

    public Optional<ApiAutomationReusableStepDetail> findDetail(String projectId, String reusableStepId) {
        return jdbc.sql("""
                SELECT id, organization_id, project_id, name, description, version, steps, created_by, created_at, updated_at
                FROM api_automation_reusable_step WHERE project_id = :projectId AND id = :reusableStepId
                """).param("projectId", projectId).param("reusableStepId", reusableStepId).query((rs, row) ->
                new ApiAutomationReusableStepDetail(rs.getString("id"), rs.getString("organization_id"),
                        rs.getString("project_id"), rs.getString("name"), rs.getString("description"), rs.getInt("version"),
                        steps(rs.getString("steps")), rs.getString("created_by"), instant(rs.getTimestamp("created_at")),
                        instant(rs.getTimestamp("updated_at")))).optional();
    }

    public Optional<ApiAutomationReusableStepRevisionSnapshot> findRevisionSnapshot(String projectId, String reusableStepId,
                                                                                       int version) {
        return jdbc.sql("""
                SELECT snapshot FROM api_automation_reusable_step_revision
                WHERE project_id = :projectId AND reusable_step_id = :reusableStepId AND version = :version
                """).param("projectId", projectId).param("reusableStepId", reusableStepId).param("version", version)
                .query((rs, row) -> snapshot(rs.getString("snapshot"))).optional();
    }

    public int countByProject(String projectId) {
        Integer value = jdbc.sql("SELECT COUNT(*) FROM api_automation_reusable_step WHERE project_id = :projectId")
                .param("projectId", projectId).query(Integer.class).single();
        return value == null ? 0 : value;
    }

    public void insert(String id, String organizationId, String projectId, String name, String description, String steps,
                       String createdBy) {
        jdbc.sql("""
                INSERT INTO api_automation_reusable_step
                    (id, organization_id, project_id, name, description, version, steps, created_by)
                VALUES (:id, :organizationId, :projectId, :name, :description, 1, CAST(:steps AS JSON), :createdBy)
                """).param("id", id).param("organizationId", organizationId).param("projectId", projectId).param("name", name)
                .param("description", description).param("steps", steps).param("createdBy", createdBy).update();
    }

    public int update(String projectId, String reusableStepId, int expectedVersion, String name, String description,
                      String steps) {
        return jdbc.sql("""
                UPDATE api_automation_reusable_step
                SET name = :name, description = :description, steps = CAST(:steps AS JSON), version = version + 1,
                    updated_at = CURRENT_TIMESTAMP(3)
                WHERE project_id = :projectId AND id = :reusableStepId AND version = :expectedVersion
                """).param("projectId", projectId).param("reusableStepId", reusableStepId).param("expectedVersion", expectedVersion)
                .param("name", name).param("description", description).param("steps", steps).update();
    }

    public void insertRevision(String id, String reusableStepId, String organizationId, String projectId, int version,
                               String snapshot, String createdBy) {
        jdbc.sql("""
                INSERT INTO api_automation_reusable_step_revision
                    (id, reusable_step_id, organization_id, project_id, version, snapshot, created_by)
                VALUES (:id, :reusableStepId, :organizationId, :projectId, :version, CAST(:snapshot AS JSON), :createdBy)
                """).param("id", id).param("reusableStepId", reusableStepId).param("organizationId", organizationId)
                .param("projectId", projectId).param("version", version).param("snapshot", snapshot).param("createdBy", createdBy).update();
    }

    public String json(Object source) {
        try {
            return json.writeValueAsString(source);
        } catch (Exception exception) {
            throw new IllegalStateException("公共步骤无法序列化", exception);
        }
    }

    private List<AutomationStep> steps(String source) {
        try {
            return json.readValue(source, new TypeReference<>() { });
        } catch (Exception exception) {
            throw new IllegalStateException("已保存的公共步骤格式不合法", exception);
        }
    }

    private ApiAutomationReusableStepRevisionSnapshot snapshot(String source) {
        try {
            return json.readValue(source, ApiAutomationReusableStepRevisionSnapshot.class);
        } catch (Exception exception) {
            throw new IllegalStateException("已保存的公共步骤修订格式不合法", exception);
        }
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
