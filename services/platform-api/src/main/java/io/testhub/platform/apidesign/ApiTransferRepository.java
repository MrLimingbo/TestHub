package io.testhub.platform.apidesign;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence needed only by native transfer; normal API writes stay in the domain service. */
@Repository
public class ApiTransferRepository {
    private final JdbcClient jdbc;

    public ApiTransferRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void lockProject(String projectId) {
        // A current locking read also serializes the first request, before a receipt exists.
        jdbc.sql("SELECT id FROM project WHERE id=:project FOR UPDATE")
                .param("project", projectId).query(String.class).single();
    }

    public Optional<StoredReceipt> receipt(String projectId, String userId, String requestKey) {
        return jdbc.sql("""
                SELECT payload_sha256, result_json FROM api_transfer_receipt
                WHERE project_id=:project AND requested_by=:user AND request_key=:key FOR UPDATE
                """).param("project", projectId).param("user", userId).param("key", requestKey)
                .query((rs, row) -> new StoredReceipt(rs.getString(1), rs.getString(2))).optional();
    }

    public boolean routeExists(String projectId, String method, String path) {
        // Use the database's own collation, including on MySQL, rather than Java equality.
        return jdbc.sql("SELECT COUNT(*) FROM api_definition WHERE project_id=:project AND method=:method AND path=:path")
                .param("project", projectId).param("method", method).param("path", path).query(Integer.class).single() > 0;
    }

    public boolean lockEnabledEnvironment(String projectId, String code) {
        return jdbc.sql("SELECT enabled FROM project_environment WHERE project_id=:project AND environment_code=:code FOR UPDATE")
                .param("project", projectId).param("code", code).query(Boolean.class).optional().orElse(false);
    }

    public List<String> lockParameterKeys(String projectId, String code) {
        // Never read or decrypt credentials during dependency validation.
        return jdbc.sql("SELECT parameter_key FROM project_parameter WHERE project_id=:project AND environment_code=:code FOR UPDATE")
                .param("project", projectId).param("code", code).query(String.class).list();
    }

    public void restoreVersion(String projectId, String apiId, String versionId, ApiTransferService.Version source) {
        jdbc.sql("""
                UPDATE api_definition_version SET status=:status, published_by=NULL,
                    published_at=:published, created_at=:created
                WHERE id=:id AND api_id=:api AND EXISTS
                    (SELECT 1 FROM api_definition WHERE id=:api AND project_id=:project)
                """).param("status", source.status()).param("published", source.publishedAt() == null ? null : Timestamp.from(source.publishedAt()))
                .param("created", Timestamp.from(source.createdAt())).param("id", versionId)
                .param("api", apiId).param("project", projectId).update();
    }

    public void saveReceipt(String id, String organizationId, String projectId, String userId,
                            String key, String hash, String result) {
        jdbc.sql("""
                INSERT INTO api_transfer_receipt
                    (id,organization_id,project_id,requested_by,request_key,payload_sha256,result_json)
                VALUES (:id,:org,:project,:user,:key,:hash,:result)
                """).param("id", id).param("org", organizationId).param("project", projectId).param("user", userId)
                .param("key", key).param("hash", hash).param("result", result).update();
    }

    public record StoredReceipt(String hash, String result) { }
}
