package io.testhub.platform.access;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testhub.platform.account.UserAccount;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TokenRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public TokenRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<ProjectAccessToken> findActiveAndRevokedForProject(String projectId) {
        return jdbc.sql("""
                SELECT id, name, token_prefix, scopes, expires_at, last_used_at, revoked_at, created_at
                FROM personal_access_token WHERE project_id = :projectId ORDER BY created_at DESC
                """).param("projectId", projectId).query((rs, rowNumber) -> new ProjectAccessToken(
                rs.getString("id"), rs.getString("name"), rs.getString("token_prefix"), parseScopes(rs.getString("scopes")),
                instant(rs.getTimestamp("expires_at")), instant(rs.getTimestamp("last_used_at")),
                instant(rs.getTimestamp("revoked_at")), instant(rs.getTimestamp("created_at")))).list();
    }

    public void insert(String id, String userId, String projectId, String name, String prefix, String tokenHash,
                       List<String> scopes, Instant expiresAt) {
        jdbc.sql("""
                INSERT INTO personal_access_token
                    (id, user_id, project_id, name, token_prefix, token_hash, scopes, expires_at)
                VALUES (:id, :userId, :projectId, :name, :prefix, :tokenHash, :scopes, :expiresAt)
                """).param("id", id).param("userId", userId).param("projectId", projectId).param("name", name)
                .param("prefix", prefix).param("tokenHash", tokenHash).param("scopes", toJson(scopes))
                .param("expiresAt", expiresAt).update();
    }

    public void revoke(String tokenId, String projectId) {
        jdbc.sql("""
                UPDATE personal_access_token SET revoked_at = CURRENT_TIMESTAMP(3)
                WHERE id = :tokenId AND project_id = :projectId AND revoked_at IS NULL
                """).param("tokenId", tokenId).param("projectId", projectId).update();
    }

    public Optional<TokenAuthentication> findActiveByHash(String tokenHash) {
        return jdbc.sql("""
                SELECT t.id, t.project_id, t.scopes, u.id AS user_id, u.account, u.email, u.display_name,
                       u.password_hash, u.enabled
                FROM personal_access_token t JOIN user_account u ON u.id = t.user_id
                WHERE t.token_hash = :tokenHash AND t.revoked_at IS NULL AND u.enabled = TRUE
                  AND (t.expires_at IS NULL OR t.expires_at > CURRENT_TIMESTAMP(3))
                """).param("tokenHash", tokenHash).query((rs, rowNumber) -> new TokenAuthentication(
                rs.getString("id"), rs.getString("project_id"), parseScopes(rs.getString("scopes")), new UserAccount(
                rs.getString("user_id"), rs.getString("account"), rs.getString("email"), rs.getString("display_name"),
                rs.getString("password_hash"), rs.getBoolean("enabled")))).optional();
    }

    public void markUsed(String tokenId) {
        jdbc.sql("UPDATE personal_access_token SET last_used_at = CURRENT_TIMESTAMP(3) WHERE id = :tokenId")
                .param("tokenId", tokenId).update();
    }

    private List<String> parseScopes(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (Exception exception) {
            throw new IllegalStateException("令牌权限数据损坏", exception);
        }
    }

    private String toJson(List<String> scopes) {
        try {
            return objectMapper.writeValueAsString(scopes);
        } catch (Exception exception) {
            throw new IllegalStateException("无法序列化令牌权限", exception);
        }
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record TokenAuthentication(String tokenId, String projectId, List<String> scopes, UserAccount account) {
    }
}
