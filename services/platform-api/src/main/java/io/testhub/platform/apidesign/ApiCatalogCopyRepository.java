package io.testhub.platform.apidesign;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ApiCatalogCopyRepository {
    private final JdbcClient jdbc;

    public ApiCatalogCopyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Receipt> find(String organization, String project, String user, String key) {
        return jdbc.sql("""
                SELECT payload_sha256, result_json FROM api_catalog_copy_receipt
                WHERE organization_id = :organization AND project_id = :project
                    AND requested_by = :user AND request_key = :key
                FOR UPDATE
                """).param("organization", organization).param("project", project).param("user", user).param("key", key)
                .query((rs, row) -> new Receipt(rs.getString(1), rs.getString(2))).optional();
    }

    public void insert(String id, String organization, String project, String user, String key, String hash, String result) {
        jdbc.sql("""
                INSERT INTO api_catalog_copy_receipt
                    (id, organization_id, project_id, requested_by, request_key, payload_sha256, result_json)
                VALUES (:id, :organization, :project, :user, :key, :hash, :result)
                """).param("id", id).param("organization", organization).param("project", project).param("user", user)
                .param("key", key).param("hash", hash).param("result", result).update();
    }

    public record Receipt(String payloadSha256, String resultJson) { }
}
