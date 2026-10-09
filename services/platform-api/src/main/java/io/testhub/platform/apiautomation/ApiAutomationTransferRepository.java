package io.testhub.platform.apiautomation;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ApiAutomationTransferRepository {
    private final JdbcClient jdbc;

    public ApiAutomationTransferRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void lockProject(String projectId) {
        jdbc.sql("SELECT id FROM project WHERE id = :projectId FOR UPDATE")
                .param("projectId", projectId).query(String.class).single();
    }

    public Optional<StoredReceipt> receipt(String projectId, String userId, String requestKey) {
        return jdbc.sql("""
                SELECT payload_sha256, result_json FROM api_automation_transfer_receipt
                WHERE project_id = :projectId AND requested_by = :userId AND request_key = :requestKey FOR UPDATE
                """).param("projectId", projectId).param("userId", userId).param("requestKey", requestKey)
                .query((rs, row) -> new StoredReceipt(rs.getString(1), rs.getString(2))).optional();
    }

    public void saveReceipt(String id, String organizationId, String projectId, String userId,
                            String requestKey, String hash, String result) {
        jdbc.sql("""
                INSERT INTO api_automation_transfer_receipt
                    (id, organization_id, project_id, requested_by, request_key, payload_sha256, result_json)
                VALUES (:id, :organizationId, :projectId, :userId, :requestKey, :hash, :result)
                """).param("id", id).param("organizationId", organizationId).param("projectId", projectId)
                .param("userId", userId).param("requestKey", requestKey).param("hash", hash)
                .param("result", result).update();
    }

    public void restoreProfileVersion(String projectId, String profileId, int version) {
        jdbc.sql("UPDATE api_automation_execution_profile SET version = :version WHERE project_id = :projectId AND id = :profileId")
                .param("version", version).param("projectId", projectId).param("profileId", profileId).update();
    }

    public record StoredReceipt(String hash, String result) { }
}
