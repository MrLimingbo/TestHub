package io.testhub.platform.performance;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PerformanceTestExecutionArtifactRepository {
    private final JdbcClient jdbc;

    public PerformanceTestExecutionArtifactRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<PerformanceTestExecutionArtifact> findByExecution(String projectId, String executionId) {
        return jdbc.sql("""
                SELECT id, execution_id, artifact_type, original_name, content_type, size_bytes, checksum, runner_id, created_at
                FROM performance_test_execution_artifact
                WHERE project_id = :projectId AND execution_id = :executionId
                ORDER BY created_at ASC, id ASC
                """).param("projectId", projectId).param("executionId", executionId).query((rs, row) -> map(
                rs.getString("id"), rs.getString("execution_id"), rs.getString("artifact_type"), rs.getString("original_name"),
                rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("checksum"), rs.getString("runner_id"),
                rs.getTimestamp("created_at"))).list();
    }

    public Optional<StoredArtifact> findByType(String executionId, String artifactType) {
        return jdbc.sql("""
                SELECT id, execution_id, project_id, object_key, artifact_type, original_name, content_type, size_bytes,
                       checksum, runner_id, created_at
                FROM performance_test_execution_artifact
                WHERE execution_id = :executionId AND artifact_type = :artifactType
                """).param("executionId", executionId).param("artifactType", artifactType).query((rs, row) -> new StoredArtifact(
                map(rs.getString("id"), rs.getString("execution_id"), rs.getString("artifact_type"), rs.getString("original_name"),
                        rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("checksum"), rs.getString("runner_id"),
                        rs.getTimestamp("created_at")), rs.getString("project_id"), rs.getString("object_key"))).optional();
    }

    public Optional<StoredArtifact> findStored(String projectId, String executionId, String artifactId) {
        return jdbc.sql("""
                SELECT id, execution_id, project_id, object_key, artifact_type, original_name, content_type, size_bytes,
                       checksum, runner_id, created_at
                FROM performance_test_execution_artifact
                WHERE project_id = :projectId AND execution_id = :executionId AND id = :artifactId
                """).param("projectId", projectId).param("executionId", executionId).param("artifactId", artifactId)
                .query((rs, row) -> new StoredArtifact(map(rs.getString("id"), rs.getString("execution_id"),
                        rs.getString("artifact_type"), rs.getString("original_name"), rs.getString("content_type"),
                        rs.getLong("size_bytes"), rs.getString("checksum"), rs.getString("runner_id"), rs.getTimestamp("created_at")),
                        rs.getString("project_id"), rs.getString("object_key"))).optional();
    }

    public void insert(String id, PerformanceTestExecution execution, String artifactType, String objectKey, String originalName,
                       String contentType, long sizeBytes, String checksum, String runnerId) {
        jdbc.sql("""
                INSERT INTO performance_test_execution_artifact
                    (id, execution_id, organization_id, project_id, artifact_type, object_key, original_name, content_type,
                     size_bytes, checksum, runner_id)
                VALUES (:id, :executionId, :organizationId, :projectId, :artifactType, :objectKey, :originalName, :contentType,
                        :sizeBytes, :checksum, :runnerId)
                """).param("id", id).param("executionId", execution.id()).param("organizationId", execution.organizationId())
                .param("projectId", execution.projectId()).param("artifactType", artifactType).param("objectKey", objectKey)
                .param("originalName", originalName).param("contentType", contentType).param("sizeBytes", sizeBytes)
                .param("checksum", checksum).param("runnerId", runnerId).update();
    }

    private PerformanceTestExecutionArtifact map(String id, String executionId, String artifactType, String originalName,
                                                 String contentType, long sizeBytes, String checksum, String runnerId,
                                                 Timestamp createdAt) {
        return new PerformanceTestExecutionArtifact(id, executionId, artifactType, originalName, contentType, sizeBytes,
                checksum, runnerId, createdAt == null ? null : Instant.ofEpochMilli(createdAt.getTime()));
    }

    public record StoredArtifact(PerformanceTestExecutionArtifact artifact, String projectId, String objectKey) {
    }
}
