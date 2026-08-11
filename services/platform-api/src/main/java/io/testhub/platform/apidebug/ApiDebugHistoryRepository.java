package io.testhub.platform.apidebug;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ApiDebugHistoryRepository {
    private final JdbcClient jdbc;

    public ApiDebugHistoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ApiDebugHistory entry) {
        jdbc.sql("""
                INSERT INTO api_debug_history
                    (id, organization_id, project_id, method, protocol, request_url, response_status, duration_ms,
                     response_size, result, error_message, replay_draft_ciphertext, created_by)
                VALUES (:id, :organizationId, :projectId, :method, :protocol, :requestUrl, :responseStatus, :durationMs,
                        :responseSize, :result, :errorMessage, :replayDraft, :createdBy)
                """).param("id", entry.id()).param("organizationId", entry.organizationId())
                .param("projectId", entry.projectId()).param("method", entry.method())
                .param("protocol", entry.protocol())
                .param("requestUrl", entry.requestUrl()).param("responseStatus", entry.responseStatus())
                .param("durationMs", entry.durationMs()).param("responseSize", entry.responseSize())
                .param("result", entry.result()).param("errorMessage", entry.errorMessage())
                .param("replayDraft", entry.replayDraft())
                .param("createdBy", entry.createdBy()).update();
    }

    public List<ApiDebugHistory> findRecent(String projectId, int limit) {
        return jdbc.sql("""
                SELECT id, organization_id, project_id, method, protocol, request_url, response_status, duration_ms,
                       response_size, result, error_message, replay_draft_ciphertext, created_by, created_at
                FROM api_debug_history
                WHERE project_id = :projectId
                ORDER BY created_at DESC LIMIT :limit
                """).param("projectId", projectId).param("limit", limit).query((rs, rowNumber) -> new ApiDebugHistory(
                rs.getString("id"), rs.getString("organization_id"), rs.getString("project_id"), rs.getString("method"),
                rs.getString("protocol"), rs.getString("request_url"), rs.getObject("response_status", Integer.class), rs.getInt("duration_ms"),
                rs.getInt("response_size"), rs.getString("result"), rs.getString("error_message"), rs.getString("replay_draft_ciphertext"),
                rs.getString("created_by"),
                instant(rs.getTimestamp("created_at")))).list();
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
