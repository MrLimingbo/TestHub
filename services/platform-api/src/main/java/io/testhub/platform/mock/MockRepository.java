package io.testhub.platform.mock;

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
public class MockRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public MockRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void lockProject(String projectId) {
        jdbc.sql("SELECT id FROM project WHERE id=:project FOR UPDATE").param("project",projectId).query(String.class).single();
    }

    public void updateRuleDefinition(String projectId, String serverId, String ruleId, MockService.RuleInput input) {
        jdbc.sql("""
                UPDATE mock_rule SET name=:name,http_method=:method,path=:path,priority=:priority,
                    request_query=CAST(:query AS JSON),request_headers=CAST(:headers AS JSON),
                    request_body_match=:match,request_body=:body,response_status=:status,
                    response_headers=CAST(:responseHeaders AS JSON),response_body=:responseBody
                WHERE project_id=:project AND server_id=:server AND id=:id
                """).param("project",projectId).param("server",serverId).param("id",ruleId)
                .param("name",input.name()).param("method",input.httpMethod()).param("path",input.path()).param("priority",input.priority())
                .param("query",encoded(input.requestQuery())).param("headers",encoded(input.requestHeaders()))
                .param("match",input.requestBodyMatch()).param("body",input.requestBody()).param("status",input.responseStatus())
                .param("responseHeaders",encoded(input.responseHeaders())).param("responseBody",input.responseBody()).update();
    }
    private String encoded(Object value) {
        try { return json.writeValueAsString(value); }
        catch (java.io.IOException error) { throw new IllegalStateException("Mock 规则序列化失败",error); }
    }

    public List<MockServer> findServers(String projectId) {
        return jdbc.sql(serverQuery("WHERE s.project_id = :projectId"))
                .param("projectId", projectId).query((rs, row) -> server(rs)).list();
    }

    public Optional<MockServer> findServer(String projectId, String serverId) {
        return jdbc.sql(serverQuery("WHERE s.project_id = :projectId AND s.id = :serverId"))
                .param("projectId", projectId).param("serverId", serverId).query((rs, row) -> server(rs)).optional();
    }

    public Optional<MockServer> findRuntimeServer(String publicKey) {
        return jdbc.sql(serverQuery("WHERE s.public_key = :publicKey AND s.enabled = TRUE"))
                .param("publicKey", publicKey).query((rs, row) -> server(rs)).optional();
    }

    public int countServers(String projectId) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM mock_server WHERE project_id = :projectId")
                .param("projectId", projectId).query(Integer.class).single();
        return count == null ? 0 : count;
    }

    public void insertServer(String id, String organizationId, String projectId, String publicKey, String name, String userId) {
        jdbc.sql("""
                INSERT INTO mock_server (id, organization_id, project_id, public_key, name, enabled, created_by)
                VALUES (:id, :organizationId, :projectId, :publicKey, :name, TRUE, :userId)
                """).param("id", id).param("organizationId", organizationId).param("projectId", projectId).param("publicKey", publicKey)
                .param("name", name).param("userId", userId).update();
    }

    public void updateServerEnabled(String projectId, String serverId, boolean enabled) {
        jdbc.sql("UPDATE mock_server SET enabled = :enabled WHERE project_id = :projectId AND id = :serverId")
                .param("enabled", enabled).param("projectId", projectId).param("serverId", serverId).update();
    }

    public void updateSmartExampleEnabled(String projectId, String serverId, boolean enabled) {
        jdbc.sql("UPDATE mock_server SET smart_example_enabled = :enabled WHERE project_id = :projectId AND id = :serverId")
                .param("enabled", enabled).param("projectId", projectId).param("serverId", serverId).update();
    }

    public void updateRuleEnabled(String projectId, String serverId, String ruleId, boolean enabled) {
        jdbc.sql("UPDATE mock_rule SET enabled = :enabled WHERE project_id = :projectId AND server_id = :serverId AND id = :ruleId")
                .param("enabled", enabled).param("projectId", projectId).param("serverId", serverId)
                .param("ruleId", ruleId).update();
    }

    public List<MockShare> findShares(String projectId, String serverId) {
        return jdbc.sql(shareQuery("WHERE s.project_id = :projectId AND s.server_id = :serverId"))
                .param("projectId", projectId).param("serverId", serverId).query((rs, row) -> share(rs)).list();
    }

    public Optional<MockShare> findShare(String projectId, String serverId, String shareId) {
        return jdbc.sql(shareQuery("WHERE s.project_id = :projectId AND s.server_id = :serverId AND s.id = :shareId"))
                .param("projectId", projectId).param("serverId", serverId).param("shareId", shareId)
                .query((rs, row) -> share(rs)).optional();
    }

    public int countShares(String serverId) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM mock_share WHERE server_id = :serverId")
                .param("serverId", serverId).query(Integer.class).single();
        return count == null ? 0 : count;
    }

    public int countActiveShares(String serverId) {
        Integer count = jdbc.sql("""
                SELECT COUNT(*) FROM mock_share
                WHERE server_id = :serverId AND revoked_at IS NULL
                  AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP(3))
                """).param("serverId", serverId).query(Integer.class).single();
        return count == null ? 0 : count;
    }

    public void insertShare(String id, String serverId, String organizationId, String projectId, String shareKey, String name,
                            Instant expiresAt, String userId) {
        jdbc.sql("""
                INSERT INTO mock_share (id, server_id, organization_id, project_id, share_key, name, expires_at, created_by)
                VALUES (:id, :serverId, :organizationId, :projectId, :shareKey, :name, :expiresAt, :userId)
                """).param("id", id).param("serverId", serverId).param("organizationId", organizationId).param("projectId", projectId)
                .param("shareKey", shareKey).param("name", name).param("expiresAt", expiresAt).param("userId", userId).update();
    }

    public void revokeShare(String projectId, String serverId, String shareId) {
        jdbc.sql("""
                UPDATE mock_share SET revoked_at = CURRENT_TIMESTAMP(3)
                WHERE project_id = :projectId AND server_id = :serverId AND id = :shareId AND revoked_at IS NULL
                """).param("projectId", projectId).param("serverId", serverId).param("shareId", shareId).update();
    }

    public Optional<PublicMockShare> findActiveShare(String shareKey) {
        return jdbc.sql("""
                SELECT sh.share_key, sh.name AS share_name, sh.expires_at, s.id AS server_id, s.name AS server_name,
                       s.public_key, s.enabled, s.smart_example_enabled
                FROM mock_share sh JOIN mock_server s ON s.id = sh.server_id
                WHERE sh.share_key = :shareKey AND sh.revoked_at IS NULL
                  AND (sh.expires_at IS NULL OR sh.expires_at > CURRENT_TIMESTAMP(3))
                """).param("shareKey", shareKey).query((rs, row) -> new PublicMockShare(rs.getString("share_key"),
                rs.getString("share_name"), instant(rs.getTimestamp("expires_at")), rs.getString("server_id"),
                rs.getString("server_name"), rs.getString("public_key"), rs.getBoolean("enabled"),
                rs.getBoolean("smart_example_enabled"))).optional();
    }

    public List<SharedMockRule> findSharedRules(String serverId) {
        return jdbc.sql("""
                SELECT name, http_method, path, priority, response_status, request_query, request_headers, request_body_match
                FROM mock_rule WHERE server_id = :serverId AND enabled = TRUE
                ORDER BY priority DESC, created_at ASC, id ASC
                """).param("serverId", serverId).query((rs, row) -> new SharedMockRule(rs.getString("name"),
                rs.getString("http_method"), rs.getString("path"), rs.getInt("priority"), rs.getInt("response_status"),
                headers(rs.getString("request_query")), headers(rs.getString("request_headers")),
                rs.getString("request_body_match"))).list();
    }

    public List<MockRule> findRules(String projectId, String serverId) {
        return jdbc.sql(ruleQuery("WHERE r.project_id = :projectId AND r.server_id = :serverId"))
                .param("projectId", projectId).param("serverId", serverId).query((rs, row) -> rule(rs)).list();
    }

    public int countRules(String serverId) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM mock_rule WHERE server_id = :serverId")
                .param("serverId", serverId).query(Integer.class).single();
        return count == null ? 0 : count;
    }

    public void insertRule(String id, String serverId, String organizationId, String projectId, String name, String method, String path,
                           int priority, String requestQuery, String requestHeaders, String requestBodyMatch, String requestBody,
                           int responseStatus, String responseHeaders, String responseBody, String userId) {
        jdbc.sql("""
                INSERT INTO mock_rule (id, server_id, organization_id, project_id, name, http_method, path, priority,
                                       request_query, request_headers, request_body_match, request_body, response_status,
                                       response_headers, response_body, enabled, created_by)
                VALUES (:id, :serverId, :organizationId, :projectId, :name, :method, :path, :priority,
                        CAST(:requestQuery AS JSON), CAST(:requestHeaders AS JSON), :requestBodyMatch, :requestBody,
                        :responseStatus, CAST(:responseHeaders AS JSON), :responseBody, TRUE, :userId)
                """).param("id", id).param("serverId", serverId).param("organizationId", organizationId).param("projectId", projectId)
                .param("name", name).param("method", method).param("path", path).param("responseStatus", responseStatus)
                .param("priority", priority).param("requestQuery", requestQuery).param("requestHeaders", requestHeaders)
                .param("requestBodyMatch", requestBodyMatch).param("requestBody", requestBody).param("responseHeaders", responseHeaders)
                .param("responseBody", responseBody).param("userId", userId).update();
    }

    public List<MockRule> findRuntimeRules(String publicKey, String method, String path) {
        return jdbc.sql("""
                SELECT r.id, r.server_id, r.organization_id, r.project_id, r.name, r.http_method, r.path, r.priority,
                       r.request_query, r.request_headers, r.request_body_match, r.request_body, r.response_status,
                       r.response_headers, r.response_body, r.enabled, r.created_by, r.created_at, r.updated_at
                FROM mock_rule r JOIN mock_server s ON s.id = r.server_id
                WHERE s.public_key = :publicKey AND s.enabled = TRUE AND r.enabled = TRUE
                  AND r.http_method = :method AND r.path = :path
                ORDER BY r.priority DESC, r.created_at ASC, r.id ASC
                """).param("publicKey", publicKey).param("method", method).param("path", path)
                .query((rs, row) -> rule(rs)).list();
    }

    private String serverQuery(String where) {
        return """
                SELECT s.id, s.organization_id, s.project_id, s.public_key, s.name, s.enabled, s.smart_example_enabled, s.created_by, s.created_at, s.updated_at,
                       COUNT(r.id) AS rule_count
                FROM mock_server s LEFT JOIN mock_rule r ON r.server_id = s.id
                """ + where + "\n" + """
                GROUP BY s.id, s.organization_id, s.project_id, s.public_key, s.name, s.enabled, s.smart_example_enabled, s.created_by, s.created_at, s.updated_at
                ORDER BY s.updated_at DESC, s.name
                """;
    }

    private String ruleQuery(String where) {
        return """
                SELECT r.id, r.server_id, r.organization_id, r.project_id, r.name, r.http_method, r.path, r.priority,
                       r.request_query, r.request_headers, r.request_body_match, r.request_body, r.response_status,
                       r.response_headers, r.response_body, r.enabled, r.created_by, r.created_at, r.updated_at
                FROM mock_rule r
                """ + where + "\nORDER BY r.priority DESC, r.updated_at DESC, r.http_method, r.path";
    }

    private String shareQuery(String where) {
        return """
                SELECT s.id, s.server_id, s.organization_id, s.project_id, s.share_key, s.name, s.expires_at, s.revoked_at,
                       s.created_by, s.created_at
                FROM mock_share s
                """ + where + "\nORDER BY s.created_at DESC, s.id DESC";
    }

    private MockServer server(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new MockServer(rs.getString("id"), rs.getString("organization_id"), rs.getString("project_id"), rs.getString("public_key"),
                rs.getString("name"), rs.getBoolean("enabled"), rs.getBoolean("smart_example_enabled"), rs.getString("created_by"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")), rs.getInt("rule_count"));
    }

    private MockRule rule(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new MockRule(rs.getString("id"), rs.getString("server_id"), rs.getString("organization_id"), rs.getString("project_id"),
                rs.getString("name"), rs.getString("http_method"), rs.getString("path"), rs.getInt("priority"),
                headers(rs.getString("request_query")), headers(rs.getString("request_headers")), rs.getString("request_body_match"),
                rs.getString("request_body"), rs.getInt("response_status"), headers(rs.getString("response_headers")),
                rs.getString("response_body"), rs.getBoolean("enabled"), rs.getString("created_by"), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")));
    }

    private MockShare share(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new MockShare(rs.getString("id"), rs.getString("server_id"), rs.getString("organization_id"),
                rs.getString("project_id"), rs.getString("share_key"), rs.getString("name"), instant(rs.getTimestamp("expires_at")),
                instant(rs.getTimestamp("revoked_at")), rs.getString("created_by"), instant(rs.getTimestamp("created_at")));
    }

    private Map<String, String> headers(String value) {
        try {
            Map<String, String> parsed = json.readValue(value == null || value.isBlank() ? "{}" : value, new TypeReference<>() { });
            return Map.copyOf(new LinkedHashMap<>(parsed));
        } catch (Exception exception) {
            throw new IllegalStateException("已保存的 Mock 响应头格式不合法", exception);
        }
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
