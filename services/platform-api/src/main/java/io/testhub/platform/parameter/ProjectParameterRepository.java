package io.testhub.platform.parameter;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class ProjectParameterRepository {
    private final JdbcClient jdbc;

    public ProjectParameterRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void lockProject(String projectId) {
        jdbc.sql("SELECT id FROM project WHERE id = :id FOR UPDATE").param("id", projectId)
                .query(String.class).single();
    }

    public List<ProjectParameter> findByProject(String projectId, String environmentCode) {
        return jdbc.sql("""
                SELECT id, environment_code, parameter_key, parameter_value, secret, updated_by, updated_at
                FROM project_parameter
                WHERE project_id = :projectId AND secret = FALSE
                  AND (:environmentCode IS NULL OR environment_code = :environmentCode)
                ORDER BY environment_code, parameter_key
                """).param("projectId", projectId).param("environmentCode", environmentCode)
                .query((rs, rowNumber) -> new ProjectParameter(rs.getString("id"), rs.getString("environment_code"),
                        rs.getString("parameter_key"), rs.getString("parameter_value"), rs.getBoolean("secret"),
                        rs.getString("updated_by"), timestamp(rs.getTimestamp("updated_at")))).list();
    }

    public List<String> findNonSecretKeysByProject(String projectId, String environmentCode) {
        return jdbc.sql("""
                SELECT parameter_key
                FROM project_parameter
                WHERE project_id = :projectId AND secret = FALSE AND environment_code = :environmentCode
                ORDER BY parameter_key
                """).param("projectId", projectId).param("environmentCode", environmentCode)
                .query(String.class).list();
    }

    public List<String> findEnvironmentCodes(String projectId) {
        return jdbc.sql("""
                SELECT DISTINCT environment_code FROM project_parameter WHERE project_id = :projectId AND secret = FALSE
                ORDER BY environment_code
                """).param("projectId", projectId).query(String.class).list();
    }

    @Transactional
    public void upsert(String id, String organizationId, String projectId, String environmentCode, String key,
                       String value, String updatedBy) {
        requireSameType(projectId, environmentCode, key, false);
        jdbc.sql("""
                INSERT INTO project_parameter
                    (id, organization_id, project_id, environment_code, parameter_key, parameter_value, secret, updated_by)
                VALUES (:id, :organizationId, :projectId, :environmentCode, :key, :value, FALSE, :updatedBy)
                ON DUPLICATE KEY UPDATE parameter_value = VALUES(parameter_value), secret = FALSE,
                    updated_by = VALUES(updated_by), updated_at = CURRENT_TIMESTAMP(3)
                """).param("id", id).param("organizationId", organizationId).param("projectId", projectId)
                .param("environmentCode", environmentCode).param("key", key).param("value", value)
                .param("updatedBy", updatedBy).update();
    }

    public void delete(String id, String projectId) {
        jdbc.sql("DELETE FROM project_parameter WHERE id = :id AND project_id = :projectId AND secret = FALSE")
                .param("id", id).param("projectId", projectId).update();
    }

    public Optional<String> findNonSecretKey(String id, String projectId) {
        return jdbc.sql("SELECT parameter_key FROM project_parameter WHERE id = :id AND project_id = :projectId AND secret = FALSE")
                .param("id", id).param("projectId", projectId).query(String.class).optional();
    }

    public void deleteNonSecretKey(String projectId, String environmentCode, String key) {
        jdbc.sql("""
                DELETE FROM project_parameter
                WHERE project_id = :projectId AND environment_code = :environmentCode
                  AND parameter_key = :key AND secret = FALSE
                """).param("projectId", projectId).param("environmentCode", environmentCode).param("key", key)
                .update();
    }

    public void deleteByEnvironment(String projectId, String environmentCode) {
        jdbc.sql("""
                DELETE FROM project_parameter
                WHERE project_id = :projectId AND environment_code = :environmentCode
                """).param("projectId", projectId).param("environmentCode", environmentCode).update();
    }

    public List<EnvironmentSecret> findSecretsByProject(String projectId, String environmentCode) {
        return jdbc.sql("""
                SELECT id, environment_code, parameter_key, updated_by, updated_at
                FROM project_parameter
                WHERE project_id = :projectId AND secret = TRUE
                  AND (:environmentCode IS NULL OR environment_code = :environmentCode)
                ORDER BY environment_code, parameter_key
                """).param("projectId", projectId).param("environmentCode", environmentCode)
                .query((rs, rowNumber) -> new EnvironmentSecret(rs.getString("id"), rs.getString("environment_code"),
                        rs.getString("parameter_key"), rs.getString("updated_by"),
                        timestamp(rs.getTimestamp("updated_at")))).list();
    }

    public List<String> findSecretEnvironmentCodes(String projectId) {
        return jdbc.sql("""
                SELECT DISTINCT environment_code FROM project_parameter WHERE project_id = :projectId AND secret = TRUE
                ORDER BY environment_code
                """).param("projectId", projectId).query(String.class).list();
    }

    public List<DebugParameter> findForDebug(String projectId, String environmentCode) {
        return jdbc.sql("""
                SELECT parameter_key, parameter_value, secret
                FROM project_parameter
                WHERE project_id = :projectId AND environment_code = :environmentCode
                ORDER BY secret, parameter_key
                """).param("projectId", projectId).param("environmentCode", environmentCode)
                .query((rs, rowNumber) -> new DebugParameter(rs.getString("parameter_key"),
                        rs.getString("parameter_value"), rs.getBoolean("secret"))).list();
    }

    @Transactional
    public void upsertSecret(String id, String organizationId, String projectId, String environmentCode, String key,
                             String encryptedValue, String updatedBy) {
        requireSameType(projectId, environmentCode, key, true);
        jdbc.sql("""
                INSERT INTO project_parameter
                    (id, organization_id, project_id, environment_code, parameter_key, parameter_value, secret, updated_by)
                VALUES (:id, :organizationId, :projectId, :environmentCode, :key, :encryptedValue, TRUE, :updatedBy)
                ON DUPLICATE KEY UPDATE parameter_value = VALUES(parameter_value), secret = TRUE,
                    updated_by = VALUES(updated_by), updated_at = CURRENT_TIMESTAMP(3)
                """).param("id", id).param("organizationId", organizationId).param("projectId", projectId)
                .param("environmentCode", environmentCode).param("key", key).param("encryptedValue", encryptedValue)
                .param("updatedBy", updatedBy).update();
    }

    public void deleteSecret(String id, String projectId) {
        jdbc.sql("DELETE FROM project_parameter WHERE id = :id AND project_id = :projectId AND secret = TRUE")
                .param("id", id).param("projectId", projectId).update();
    }

    private void requireSameType(String projectId, String environmentCode, String key, boolean secret) {
        lockProject(projectId);
        var existing = jdbc.sql("""
                SELECT secret FROM project_parameter
                WHERE project_id = :project AND environment_code = :environment AND parameter_key = :key
                FOR UPDATE
                """).param("project", projectId).param("environment", environmentCode).param("key", key)
                .query(Boolean.class).optional();
        if (existing.isPresent() && existing.get() != secret) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "同名配置已存在其他类型，不能在保存时转换普通参数与密钥");
        }
    }

    public boolean isEnvironmentDisabled(String projectId, String environmentCode) {
        return jdbc.sql("SELECT enabled FROM project_environment WHERE project_id=:project AND environment_code=:environment")
                .param("project", projectId).param("environment", environmentCode).query(Boolean.class).optional()
                .map(enabled -> !enabled).orElse(false);
    }

    public void requireDefinedEnvironment(String projectId, String environmentCode) {
        // Called after the shared project lock, also held by environment deletion.
        boolean exists = jdbc.sql("SELECT EXISTS(SELECT 1 FROM project_environment WHERE project_id=:project AND environment_code=:environment)")
                .param("project", projectId).param("environment", environmentCode).query(Boolean.class).single();
        if (!exists) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "配置所属环境尚未定义，请先在环境管理中创建；旧孤立配置仅可读取或显式删除");
    }

    private Instant timestamp(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record DebugParameter(String key, String value, boolean secret) {
    }
}
