package io.testhub.platform.execution;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ExecutionTaskRepository {
    private static final String COLUMNS = "id, organization_id, project_id, task_type, name, status, progress, "
            + "requested_by, created_at, updated_at, version, source_task_id, source_status, source_version, "
            + "source_created_at, source_updated_at";
    private final JdbcClient jdbc;
    public ExecutionTaskRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public List<ExecutionTask> findByProject(String projectId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM execution_task WHERE project_id = :projectId ORDER BY created_at DESC, id DESC")
                .param("projectId", projectId).query(this::map).list();
    }
    public Optional<ExecutionTask> findById(String projectId, String taskId) { return find(projectId, taskId, false); }
    public List<ExecutionTask> findForSync(String projectId, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM execution_task WHERE project_id=:project ORDER BY id LIMIT :limit")
                .param("project", projectId).param("limit", limit).query(this::map).list();
    }
    public Optional<ExecutionTask> lock(String projectId, String taskId) { return find(projectId, taskId, true); }
    private Optional<ExecutionTask> find(String projectId, String taskId, boolean lock) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM execution_task WHERE project_id = :projectId AND id = :taskId"
                        + (lock ? " FOR UPDATE" : ""))
                .param("projectId", projectId).param("taskId", taskId).query(this::map).optional();
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
                    version = version + 1, updated_at = CURRENT_TIMESTAMP(3)
                WHERE project_id = :projectId AND id = :taskId
                """).param("projectId", projectId).param("taskId", taskId).param("name", name)
                .param("status", status).param("progress", progress).update();
    }
    private ExecutionTask map(ResultSet rs, int row) throws SQLException {
        return new ExecutionTask(rs.getString("id"), rs.getString("organization_id"), rs.getString("project_id"),
                rs.getString("task_type"), rs.getString("name"), rs.getString("status"), rs.getInt("progress"),
                rs.getString("requested_by"), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")),
                rs.getLong("version"), rs.getString("source_task_id"), rs.getString("source_status"),
                rs.getObject("source_version", Long.class), instant(rs.getTimestamp("source_created_at")),
                instant(rs.getTimestamp("source_updated_at")));
    }
    private Instant instant(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant(); }
}
