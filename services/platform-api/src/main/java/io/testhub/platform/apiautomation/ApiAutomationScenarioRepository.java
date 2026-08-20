package io.testhub.platform.apiautomation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ApiAutomationScenarioRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public ApiAutomationScenarioRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<ApiAutomationScenario> findByProject(String projectId) {
        return jdbc.sql("""
                SELECT id, organization_id, project_id, name, description, environment_code, failure_strategy, version,
                       JSON_LENGTH(steps) AS step_count, created_by, created_at, updated_at
                FROM api_automation_scenario WHERE project_id = :projectId
                ORDER BY updated_at DESC, id DESC
                """).param("projectId", projectId).query((rs, row) -> scenario(rs)).list();
    }

    public Optional<ApiAutomationScenarioDetail> findDetail(String projectId, String scenarioId) {
        return jdbc.sql("""
                SELECT id, organization_id, project_id, name, description, environment_code, failure_strategy, version,
                       steps, variables, COALESCE(pre_request_script, '') AS pre_request_script,
                       COALESCE(post_response_script, '') AS post_response_script, created_by, created_at, updated_at
                FROM api_automation_scenario WHERE project_id = :projectId AND id = :scenarioId
                """).param("projectId", projectId).param("scenarioId", scenarioId).query((rs, row) -> new ApiAutomationScenarioDetail(
                rs.getString("id"), rs.getString("organization_id"), rs.getString("project_id"), rs.getString("name"),
                rs.getString("description"), rs.getString("environment_code"), rs.getString("failure_strategy"),
                rs.getInt("version"), steps(rs.getString("steps")), variables(rs.getString("variables")),
                rs.getString("pre_request_script"), rs.getString("post_response_script"), rs.getString("created_by"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")))).optional();
    }

    public int countByProject(String projectId) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM api_automation_scenario WHERE project_id = :projectId")
                .param("projectId", projectId).query(Integer.class).single();
        return count == null ? 0 : count;
    }

    public void insert(String id, String organizationId, String projectId, String name, String description, String environmentCode,
                       String failureStrategy, String steps, String variables, String preRequestScript,
                       String postResponseScript, String createdBy) {
        jdbc.sql("""
                INSERT INTO api_automation_scenario
                    (id, organization_id, project_id, name, description, environment_code, failure_strategy, version,
                     steps, variables, pre_request_script, post_response_script, created_by)
                VALUES (:id, :organizationId, :projectId, :name, :description, :environmentCode, :failureStrategy, 1,
                        CAST(:steps AS JSON), CAST(:variables AS JSON), :preRequestScript, :postResponseScript, :createdBy)
                """).param("id", id).param("organizationId", organizationId).param("projectId", projectId).param("name", name)
                .param("description", description).param("environmentCode", environmentCode).param("failureStrategy", failureStrategy)
                .param("steps", steps).param("variables", variables).param("preRequestScript", preRequestScript)
                .param("postResponseScript", postResponseScript).param("createdBy", createdBy).update();
    }

    public int update(String projectId, String scenarioId, int expectedVersion, String name, String description,
                      String environmentCode, String failureStrategy, String steps, String variables,
                      String preRequestScript, String postResponseScript) {
        return jdbc.sql("""
                UPDATE api_automation_scenario
                SET name = :name, description = :description, environment_code = :environmentCode,
                    failure_strategy = :failureStrategy, steps = CAST(:steps AS JSON), variables = CAST(:variables AS JSON),
                    pre_request_script = :preRequestScript, post_response_script = :postResponseScript,
                    version = version + 1, updated_at = CURRENT_TIMESTAMP(3)
                WHERE project_id = :projectId AND id = :scenarioId AND version = :expectedVersion
                """).param("projectId", projectId).param("scenarioId", scenarioId).param("expectedVersion", expectedVersion)
                .param("name", name).param("description", description).param("environmentCode", environmentCode)
                .param("failureStrategy", failureStrategy).param("steps", steps).param("variables", variables)
                .param("preRequestScript", preRequestScript).param("postResponseScript", postResponseScript).update();
    }

    public int delete(String projectId, String scenarioId) {
        return jdbc.sql("DELETE FROM api_automation_scenario WHERE project_id = :projectId AND id = :scenarioId")
                .param("projectId", projectId).param("scenarioId", scenarioId).update();
    }

    public void insertRevision(String id, String scenarioId, String organizationId, String projectId, int version,
                               String snapshot, String createdBy) {
        jdbc.sql("""
                INSERT INTO api_automation_scenario_revision
                    (id, scenario_id, organization_id, project_id, version, snapshot, created_by)
                VALUES (:id, :scenarioId, :organizationId, :projectId, :version, CAST(:snapshot AS JSON), :createdBy)
                """).param("id", id).param("scenarioId", scenarioId).param("organizationId", organizationId)
                .param("projectId", projectId).param("version", version).param("snapshot", snapshot).param("createdBy", createdBy).update();
    }

    public List<ApiAutomationScenarioRevision> findRevisions(String projectId, String scenarioId) {
        return jdbc.sql("""
                SELECT id, scenario_id, version, created_by, created_at
                FROM api_automation_scenario_revision
                WHERE project_id = :projectId AND scenario_id = :scenarioId
                ORDER BY version DESC
                """).param("projectId", projectId).param("scenarioId", scenarioId).query((rs, row) -> new ApiAutomationScenarioRevision(
                rs.getString("id"), rs.getString("scenario_id"), rs.getInt("version"), rs.getString("created_by"),
                instant(rs.getTimestamp("created_at")))).list();
    }

    public Optional<ApiAutomationScenarioRevisionSnapshot> findRevisionSnapshot(String projectId, String scenarioId,
                                                                                  int version) {
        return jdbc.sql("""
                SELECT snapshot FROM api_automation_scenario_revision
                WHERE project_id = :projectId AND scenario_id = :scenarioId AND version = :version
                """).param("projectId", projectId).param("scenarioId", scenarioId).param("version", version)
                .query((rs, row) -> revisionSnapshot(rs.getString("snapshot"))).optional();
    }

    public String json(Object source) {
        try {
            return json.writeValueAsString(source);
        } catch (Exception exception) {
            throw new IllegalStateException("接口自动化场景无法序列化", exception);
        }
    }

    private ApiAutomationScenario scenario(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ApiAutomationScenario(rs.getString("id"), rs.getString("organization_id"), rs.getString("project_id"),
                rs.getString("name"), rs.getString("description"), rs.getString("environment_code"),
                rs.getString("failure_strategy"), rs.getInt("version"), rs.getInt("step_count"), rs.getString("created_by"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
    }

    private List<AutomationStep> steps(String source) {
        try {
            return json.readValue(source, new TypeReference<>() { });
        } catch (Exception exception) {
            throw new IllegalStateException("已保存的接口自动化步骤格式不合法", exception);
        }
    }

    private Map<String, String> variables(String source) {
        try {
            Map<String, String> values = json.readValue(source, new TypeReference<>() { });
            return Map.copyOf(new LinkedHashMap<>(values));
        } catch (Exception exception) {
            throw new IllegalStateException("已保存的接口自动化变量格式不合法", exception);
        }
    }

    private ApiAutomationScenarioRevisionSnapshot revisionSnapshot(String source) {
        try {
            return json.readValue(source, ApiAutomationScenarioRevisionSnapshot.class);
        } catch (Exception exception) {
            throw new IllegalStateException("已保存的接口自动化场景修订格式不合法", exception);
        }
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
