package io.testhub.platform.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AuditRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public AuditRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void insert(AuditLogEntry entry) {
        jdbc.sql("""
                INSERT INTO audit_log
                    (id, user_id, organization_id, project_id, action, resource_type, resource_id, outcome,
                     request_id, client_ip, detail)
                VALUES (:id, :userId, :organizationId, :projectId, :action, :resourceType, :resourceId, :outcome,
                        :requestId, :clientIp, CAST(:detail AS JSON))
                """).param("id", entry.id()).param("userId", entry.userId()).param("organizationId", entry.organizationId())
                .param("projectId", entry.projectId()).param("action", entry.action()).param("resourceType", entry.resourceType())
                .param("resourceId", entry.resourceId()).param("outcome", entry.outcome()).param("requestId", entry.requestId())
                .param("clientIp", entry.clientIp()).param("detail", toJson(entry.detail())).update();
    }

    public List<AuditLogEntry> findRecent(String projectId, String action, int limit) {
        return jdbc.sql("""
                SELECT id, user_id, organization_id, project_id, action, resource_type, resource_id, outcome,
                       request_id, client_ip, detail, created_at
                FROM audit_log
                WHERE (:projectId IS NULL OR project_id = :projectId)
                  AND (:action IS NULL OR action = :action)
                ORDER BY created_at DESC LIMIT :limit
                """).param("projectId", projectId).param("action", action).param("limit", limit).query((rs, rowNumber) -> new AuditLogEntry(
                rs.getString("id"), rs.getString("user_id"), rs.getString("organization_id"), rs.getString("project_id"),
                rs.getString("action"), rs.getString("resource_type"), rs.getString("resource_id"),
                rs.getString("outcome"), rs.getString("request_id"), rs.getString("client_ip"),
                fromJson(rs.getString("detail")), instant(rs.getTimestamp("created_at")))).list();
    }

    private String toJson(Map<String, Object> detail) {
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (Exception exception) {
            throw new IllegalStateException("无法序列化审计详情", exception);
        }
    }

    private Map<String, Object> fromJson(String detail) {
        try {
            return objectMapper.readValue(detail, new TypeReference<>() { });
        } catch (Exception exception) {
            throw new IllegalStateException("无法读取审计详情", exception);
        }
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
