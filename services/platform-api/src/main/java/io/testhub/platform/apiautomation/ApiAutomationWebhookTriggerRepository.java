package io.testhub.platform.apiautomation;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ApiAutomationWebhookTriggerRepository {
    private final JdbcClient jdbc;

    public ApiAutomationWebhookTriggerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ApiAutomationWebhookTrigger> findByProject(String projectId) {
        return jdbc.sql("""
                SELECT id, organization_id, project_id, scenario_id, name, enabled, created_by, created_at, updated_at
                FROM api_automation_webhook_trigger WHERE project_id = :projectId
                ORDER BY updated_at DESC, id DESC
                """).param("projectId", projectId).query((rs, row) -> trigger(rs)).list();
    }

    public Optional<StoredWebhookTrigger> findStoredById(String id) {
        return jdbc.sql("""
                SELECT id, organization_id, project_id, scenario_id, name, secret_ciphertext, enabled, created_by, created_at, updated_at
                FROM api_automation_webhook_trigger WHERE id = :id
                """).param("id", id).query((rs, row) -> new StoredWebhookTrigger(trigger(rs), rs.getString("secret_ciphertext"))).optional();
    }

    public void insert(String id, String organizationId, String projectId, String scenarioId, String name,
                       String secretCiphertext, String createdBy) {
        jdbc.sql("""
                INSERT INTO api_automation_webhook_trigger
                    (id, organization_id, project_id, scenario_id, name, secret_ciphertext, created_by)
                VALUES (:id, :organizationId, :projectId, :scenarioId, :name, :secretCiphertext, :createdBy)
                """).param("id", id).param("organizationId", organizationId).param("projectId", projectId)
                .param("scenarioId", scenarioId).param("name", name).param("secretCiphertext", secretCiphertext)
                .param("createdBy", createdBy).update();
    }

    public void setEnabled(String id, String projectId, boolean enabled) {
        jdbc.sql("""
                UPDATE api_automation_webhook_trigger SET enabled = :enabled, updated_at = CURRENT_TIMESTAMP(3)
                WHERE id = :id AND project_id = :projectId
                """).param("id", id).param("projectId", projectId).param("enabled", enabled).update();
    }

    public void delete(String id, String projectId) {
        jdbc.sql("DELETE FROM api_automation_webhook_trigger WHERE id = :id AND project_id = :projectId")
                .param("id", id).param("projectId", projectId).update();
    }

    private ApiAutomationWebhookTrigger trigger(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new ApiAutomationWebhookTrigger(resultSet.getString("id"), resultSet.getString("organization_id"),
                resultSet.getString("project_id"), resultSet.getString("scenario_id"), resultSet.getString("name"),
                resultSet.getBoolean("enabled"), resultSet.getString("created_by"), instant(resultSet.getTimestamp("created_at")),
                instant(resultSet.getTimestamp("updated_at")));
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record StoredWebhookTrigger(ApiAutomationWebhookTrigger trigger, String secretCiphertext) {
    }
}
