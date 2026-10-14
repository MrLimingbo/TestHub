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
                SELECT id, organization_id, project_id, scenario_id, name, enabled, secret_ready, created_by, created_at, updated_at
                FROM api_automation_webhook_trigger WHERE project_id = :projectId
                ORDER BY updated_at DESC, id DESC
                """).param("projectId", projectId).query((rs, row) -> trigger(rs)).list();
    }

    public Optional<StoredWebhookTrigger> findStoredById(String id) {
        return jdbc.sql("""
                SELECT id, organization_id, project_id, scenario_id, name, secret_ciphertext, enabled, secret_ready, created_by, created_at, updated_at
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

    public void insertSyncedDraft(String id, String organization, String project, String scenario, String name, String ciphertext, String actor) {
        jdbc.sql("INSERT INTO api_automation_webhook_trigger(id,organization_id,project_id,scenario_id,name,secret_ciphertext,enabled,secret_ready,created_by) VALUES(:id,:o,:p,:s,:n,:c,FALSE,FALSE,:u)")
                .param("id", id).param("o", organization).param("p", project).param("s", scenario).param("n", name).param("c", ciphertext).param("u", actor).update();
    }

    public void lock(String project, String id) {
        jdbc.sql("SELECT id FROM api_automation_webhook_trigger WHERE project_id=:p AND id=:id FOR UPDATE")
                .param("p", project).param("id", id).query(String.class).optional();
    }

    public void updateConfiguration(String project, String id, String scenario, String name) {
        jdbc.sql("UPDATE api_automation_webhook_trigger SET scenario_id=:s,name=:n,enabled=FALSE,updated_at=CURRENT_TIMESTAMP(3) WHERE project_id=:p AND id=:id")
                .param("p", project).param("id", id).param("s", scenario).param("n", name).update();
    }

    public void prepareSecret(String project, String id, String ciphertext) {
        jdbc.sql("UPDATE api_automation_webhook_trigger SET secret_ciphertext=:c,secret_ready=TRUE,enabled=FALSE,updated_at=CURRENT_TIMESTAMP(3) WHERE project_id=:p AND id=:id AND secret_ready=FALSE")
                .param("p", project).param("id", id).param("c", ciphertext).update();
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
                resultSet.getBoolean("enabled"), resultSet.getBoolean("secret_ready"), resultSet.getString("created_by"), instant(resultSet.getTimestamp("created_at")),
                instant(resultSet.getTimestamp("updated_at")));
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record StoredWebhookTrigger(ApiAutomationWebhookTrigger trigger, String secretCiphertext) {
    }
}
