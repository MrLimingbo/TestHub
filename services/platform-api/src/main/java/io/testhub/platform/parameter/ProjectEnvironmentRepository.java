package io.testhub.platform.parameter;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProjectEnvironmentRepository {
    private final JdbcClient jdbc;

    public ProjectEnvironmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ProjectEnvironment> findByProject(String projectId) {
        return jdbc.sql(selectSql() + """
                WHERE pe.project_id = :projectId
                ORDER BY pe.is_default DESC, pe.enabled DESC, pe.name, pe.environment_code
                """).param("projectId", projectId).query(this::map).list();
    }

    public Optional<ProjectEnvironment> find(String projectId, String environmentId) {
        return jdbc.sql(selectSql() + """
                WHERE pe.project_id = :projectId AND pe.id = :environmentId
                """).param("projectId", projectId).param("environmentId", environmentId)
                .query(this::map).optional();
    }

    public Optional<ProjectEnvironment> findByCode(String projectId, String environmentCode) {
        return jdbc.sql(selectSql() + """
                WHERE pe.project_id = :projectId AND pe.environment_code = :environmentCode
                """).param("projectId", projectId).param("environmentCode", environmentCode)
                .query(this::map).optional();
    }

    public void insert(String id, String organizationId, String projectId, String name, String environmentCode,
                       String baseUrl, String description, boolean enabled, boolean defaultEnvironment,
                       String userId) {
        jdbc.sql("""
                INSERT INTO project_environment
                    (id, organization_id, project_id, name, environment_code, base_url, description,
                     enabled, is_default, created_by, updated_by)
                VALUES (:id, :organizationId, :projectId, :name, :environmentCode, :baseUrl, :description,
                        :enabled, :defaultEnvironment, :userId, :userId)
                """).param("id", id).param("organizationId", organizationId).param("projectId", projectId)
                .param("name", name).param("environmentCode", environmentCode).param("baseUrl", baseUrl)
                .param("description", description).param("enabled", enabled)
                .param("defaultEnvironment", defaultEnvironment).param("userId", userId).update();
    }

    public int update(String projectId, String environmentId, String name, String baseUrl, String description,
                      boolean enabled, int expectedVersion, String userId) {
        return jdbc.sql("""
                UPDATE project_environment
                SET name = :name, base_url = :baseUrl, description = :description, enabled = :enabled,
                    updated_by = :userId, version = version + 1
                WHERE project_id = :projectId AND id = :environmentId AND version = :expectedVersion
                """).param("projectId", projectId).param("environmentId", environmentId).param("name", name)
                .param("baseUrl", baseUrl).param("description", description).param("enabled", enabled)
                .param("expectedVersion", expectedVersion).param("userId", userId).update();
    }

    public void clearDefault(String projectId) {
        jdbc.sql("UPDATE project_environment SET is_default = FALSE, version = version + 1 WHERE project_id = :projectId AND is_default = TRUE")
                .param("projectId", projectId).update();
    }

    public void setDefault(String projectId, String environmentId, String userId) {
        jdbc.sql("""
                UPDATE project_environment
                SET is_default = TRUE, enabled = TRUE, updated_by = :userId, version = version + 1
                WHERE project_id = :projectId AND id = :environmentId
                """).param("projectId", projectId).param("environmentId", environmentId).param("userId", userId)
                .update();
    }

    public int count(String projectId) {
        return jdbc.sql("SELECT COUNT(*) FROM project_environment WHERE project_id = :projectId")
                .param("projectId", projectId).query(Integer.class).single();
    }

    public int referenceCount(String projectId, String environmentCode) {
        return jdbc.sql("""
                SELECT
                    (SELECT COUNT(*) FROM api_automation_scenario
                     WHERE project_id = :projectId AND environment_code = :environmentCode)
                  + (SELECT COUNT(*) FROM api_automation_execution_profile
                     WHERE project_id = :projectId AND environment_code = :environmentCode)
                  + (SELECT COUNT(*) FROM api_definition_case
                     WHERE project_id = :projectId AND environment_code = :environmentCode)
                """).param("projectId", projectId).param("environmentCode", environmentCode)
                .query(Integer.class).single();
    }

    public void delete(String projectId, String environmentId) {
        jdbc.sql("DELETE FROM project_environment WHERE project_id = :projectId AND id = :environmentId")
                .param("projectId", projectId).param("environmentId", environmentId).update();
    }

    private String selectSql() {
        return """
                SELECT pe.id, pe.organization_id, pe.project_id, pe.name, pe.environment_code, pe.base_url,
                       pe.description, pe.enabled, pe.is_default, pe.version, pe.created_by, pe.updated_by,
                       pe.created_at, pe.updated_at,
                       (SELECT COUNT(*) FROM project_parameter variable
                        WHERE variable.project_id = pe.project_id
                          AND variable.environment_code = pe.environment_code
                          AND variable.secret = FALSE AND variable.parameter_key <> 'BASE_URL') AS variable_count,
                       (SELECT COUNT(*) FROM project_parameter secret
                        WHERE secret.project_id = pe.project_id
                          AND secret.environment_code = pe.environment_code
                          AND secret.secret = TRUE) AS secret_count
                FROM project_environment pe
                """;
    }

    private ProjectEnvironment map(java.sql.ResultSet rs, int rowNumber) throws java.sql.SQLException {
        return new ProjectEnvironment(rs.getString("id"), rs.getString("organization_id"),
                rs.getString("project_id"), rs.getString("name"), rs.getString("environment_code"),
                rs.getString("base_url"), rs.getString("description"), rs.getBoolean("enabled"),
                rs.getBoolean("is_default"), rs.getInt("variable_count"), rs.getInt("secret_count"),
                rs.getInt("version"), rs.getString("created_by"), rs.getString("updated_by"),
                timestamp(rs.getTimestamp("created_at")), timestamp(rs.getTimestamp("updated_at")));
    }

    private Instant timestamp(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
