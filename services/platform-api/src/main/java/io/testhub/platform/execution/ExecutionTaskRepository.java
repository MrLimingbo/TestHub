package io.testhub.platform.execution;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ExecutionTaskRepository {
    private final JdbcClient jdbc;

    public ExecutionTaskRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ExecutionTask> findByProject(String projectId) {
        return jdbc.sql("""
                SELECT id, organization_id, project_id, task_type, name, status, progress, requested_by, created_at, updated_at
                FROM execution_task WHERE project_id = :projectId ORDER BY created_at DESC
                """).param("projectId", projectId).query((rs, rowNumber) -> map(rs.getString("id"), rs.getString("organization_id"),
                rs.getString("project_id"), rs.getString("task_type"), rs.getString("name"), rs.getString("status"),
                rs.getInt("progress"), rs.getString("requested_by"), rs.getTimestamp("created_at"), rs.getTimestamp("updated_at"))).list();
    }

    public Optional<ExecutionTask> findById(String projectId, String taskId) {
        return jdbc.sql("""
                SELECT id, organization_id, project_id, task_type, name, status, progress, requested_by, created_at, updated_at
                FROM execution_task WHERE project_id = :projectId AND id = :taskId
                """).param("projectId", projectId).param("taskId", taskId).query((rs, rowNumber) -> map(rs.getString("id"),
                rs.getString("organization_id"), rs.getString("project_id"), rs.getString("task_type"), rs.getString("name"),
                rs.getString("status"), rs.getInt("progress"), rs.getString("requested_by"), rs.getTimestamp("created_at"),
                rs.getTimestamp("updated_at"))).optional();
    }

    public void insert(String id, String organizationId, String projectId, String taskType, String name, String requestedBy) {
        jdbc.sql("""
                INSERT INTO execution_task (id, organization_id, project_id, task_type, name, status, progress, requested_by)
                VALUES (:id, :organizationId, :projectId, :taskType, :name, 'QUEUED', 0, :requestedBy)
                """).param("id", id).param("organizationId", organizationId).param("projectId", projectId)
                .param("taskType", taskType).param("name", name).param("requestedBy", requestedBy).update();
    }

    public int update(String projectId, String taskId, String name, String status, int progress) {
        return jdbc.sql("""
                UPDATE execution_task SET name = :name, status = :status, progress = :progress,
                    updated_at = CURRENT_TIMESTAMP(3)
                WHERE project_id = :projectId AND id = :taskId
                """).param("projectId", projectId).param("taskId", taskId).param("name", name)
                .param("status", status).param("progress", progress).update();
    }

    private ExecutionTask map(String id, String organizationId, String projectId, String taskType, String name,
                               String status, int progress, String requestedBy, Timestamp createdAt, Timestamp updatedAt) {
        return new ExecutionTask(id, organizationId, projectId, taskType, name, status, progress, requestedBy,
                instant(createdAt), instant(updatedAt));
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
