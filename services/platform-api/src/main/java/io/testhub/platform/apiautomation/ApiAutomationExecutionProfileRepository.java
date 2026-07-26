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
public class ApiAutomationExecutionProfileRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public ApiAutomationExecutionProfileRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<ApiAutomationExecutionProfile> findByProject(String projectId) {
        return jdbc.sql("""
                SELECT id, organization_id, project_id, name, environment_code, version, JSON_LENGTH(variables) AS variable_count,
                       created_by, created_at, updated_at
                FROM api_automation_execution_profile WHERE project_id = :projectId
                ORDER BY updated_at DESC, id DESC
                """).param("projectId", projectId).query((rs, row) -> new ApiAutomationExecutionProfile(
                rs.getString("id"), rs.getString("organization_id"), rs.getString("project_id"), rs.getString("name"),
                rs.getString("environment_code"), rs.getInt("version"), rs.getInt("variable_count"), rs.getString("created_by"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")))).list();
    }

    public Optional<ApiAutomationExecutionProfileDetail> findDetail(String projectId, String profileId) {
        return jdbc.sql("""
                SELECT id, organization_id, project_id, name, environment_code, version, variables, created_by, created_at, updated_at
                FROM api_automation_execution_profile WHERE project_id = :projectId AND id = :profileId
                """).param("projectId", projectId).param("profileId", profileId).query((rs, row) ->
                new ApiAutomationExecutionProfileDetail(rs.getString("id"), rs.getString("organization_id"),
                        rs.getString("project_id"), rs.getString("name"), rs.getString("environment_code"), rs.getInt("version"),
                        variables(rs.getString("variables")), rs.getString("created_by"), instant(rs.getTimestamp("created_at")),
                        instant(rs.getTimestamp("updated_at")))).optional();
    }

    public int countByProject(String projectId) {
        Integer value = jdbc.sql("SELECT COUNT(*) FROM api_automation_execution_profile WHERE project_id = :projectId")
                .param("projectId", projectId).query(Integer.class).single();
        return value == null ? 0 : value;
    }

    public void insert(String id, String organizationId, String projectId, String name, String environmentCode,
                       String variables, String createdBy) {
        jdbc.sql("""
                INSERT INTO api_automation_execution_profile
                    (id, organization_id, project_id, name, environment_code, variables, version, created_by)
                VALUES (:id, :organizationId, :projectId, :name, :environmentCode, CAST(:variables AS JSON), 1, :createdBy)
                """).param("id", id).param("organizationId", organizationId).param("projectId", projectId).param("name", name)
                .param("environmentCode", environmentCode).param("variables", variables).param("createdBy", createdBy).update();
    }

    public int update(String projectId, String profileId, int expectedVersion, String name, String environmentCode,
                      String variables) {
        return jdbc.sql("""
                UPDATE api_automation_execution_profile
                SET name = :name, environment_code = :environmentCode, variables = CAST(:variables AS JSON),
                    version = version + 1, updated_at = CURRENT_TIMESTAMP(3)
                WHERE project_id = :projectId AND id = :profileId AND version = :expectedVersion
                """).param("projectId", projectId).param("profileId", profileId).param("expectedVersion", expectedVersion)
                .param("name", name).param("environmentCode", environmentCode).param("variables", variables).update();
    }

    public String json(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("执行配置集无法序列化", exception); }
    }

    private Map<String, String> variables(String source) {
        try { return Map.copyOf(new LinkedHashMap<>(json.readValue(source, new TypeReference<>() { }))); }
        catch (Exception exception) { throw new IllegalStateException("已保存的执行配置集变量格式不合法", exception); }
    }

    private Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}
