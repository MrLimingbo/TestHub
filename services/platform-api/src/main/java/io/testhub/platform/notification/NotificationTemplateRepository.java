package io.testhub.platform.notification;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationTemplateRepository {
    private final JdbcClient jdbc;

    public NotificationTemplateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void lockProject(String projectId) {
        jdbc.sql("SELECT id FROM project WHERE id=:id FOR UPDATE").param("id", projectId).query(String.class).single();
    }
    public List<NotificationTemplate> findByProject(String projectId) {
        return jdbc.sql("""
                SELECT id, organization_id, project_id, name, event_code, channel, subject_template, body_template,
                       target_url, signing_secret_ciphertext, enabled, updated_by, updated_at
                FROM notification_template WHERE project_id = :projectId ORDER BY updated_at DESC, name
                """).param("projectId", projectId).query((rs, rowNumber) -> template(rs)).list();
    }

    public java.util.Optional<StoredNotificationTemplate> findStored(String id, String projectId) {
        return jdbc.sql("""
                SELECT id, organization_id, project_id, name, event_code, channel, subject_template, body_template,
                       target_url, signing_secret_ciphertext, enabled, updated_by, updated_at
                FROM notification_template WHERE id = :id AND project_id = :projectId
                """).param("id", id).param("projectId", projectId).query((rs, rowNumber) -> new StoredNotificationTemplate(
                template(rs), rs.getString("signing_secret_ciphertext"))).optional();
    }

    public List<StoredNotificationTemplate> findEnabledWebhookTemplates(String projectId, String eventCode) {
        return jdbc.sql("""
                SELECT id, organization_id, project_id, name, event_code, channel, subject_template, body_template,
                       target_url, signing_secret_ciphertext, enabled, updated_by, updated_at
                FROM notification_template
                WHERE project_id = :projectId AND event_code = :eventCode AND channel = 'WEBHOOK' AND enabled = TRUE
                  AND target_url IS NOT NULL AND signing_secret_ciphertext IS NOT NULL
                """).param("projectId", projectId).param("eventCode", eventCode).query((rs, rowNumber) ->
                new StoredNotificationTemplate(template(rs), rs.getString("signing_secret_ciphertext"))).list();
    }

    public void insert(String id, String organizationId, String projectId, String name, String eventCode, String channel,
                       String subjectTemplate, String bodyTemplate, String targetUrl, String signingSecretCiphertext,
                       boolean enabled, String updatedBy) {
        jdbc.sql("""
                INSERT INTO notification_template
                    (id, organization_id, project_id, name, event_code, channel, subject_template, body_template,
                       target_url, signing_secret_ciphertext, enabled, updated_by)
                VALUES (:id, :organizationId, :projectId, :name, :eventCode, :channel, :subjectTemplate, :bodyTemplate,
                        :targetUrl, :signingSecretCiphertext, :enabled, :updatedBy)
                """).param("id", id).param("organizationId", organizationId).param("projectId", projectId)
                .param("name", name).param("eventCode", eventCode).param("channel", channel)
                .param("subjectTemplate", subjectTemplate).param("bodyTemplate", bodyTemplate)
                .param("targetUrl", targetUrl).param("signingSecretCiphertext", signingSecretCiphertext)
                .param("enabled", enabled).param("updatedBy", updatedBy).update();
    }

    public void update(String id, String projectId, String name, String eventCode, String channel,
                       String subjectTemplate, String bodyTemplate, String targetUrl, String signingSecretCiphertext,
                       boolean enabled, String updatedBy) {
        jdbc.sql("""
                UPDATE notification_template
                SET name = :name, event_code = :eventCode, channel = :channel, subject_template = :subjectTemplate,
                    body_template = :bodyTemplate, target_url = :targetUrl,
                    signing_secret_ciphertext = :signingSecretCiphertext, enabled = :enabled, updated_by = :updatedBy,
                    updated_at = CURRENT_TIMESTAMP(3)
                WHERE id = :id AND project_id = :projectId
                """).param("id", id).param("projectId", projectId).param("name", name).param("eventCode", eventCode)
                .param("channel", channel).param("subjectTemplate", subjectTemplate).param("bodyTemplate", bodyTemplate)
                .param("targetUrl", targetUrl).param("signingSecretCiphertext", signingSecretCiphertext)
                .param("enabled", enabled).param("updatedBy", updatedBy).update();
    }

    public void updateDefinition(String id, String projectId, String eventCode, String subject, String body, String actorId) {
        jdbc.sql("""
                UPDATE notification_template SET event_code=:event, subject_template=:subject, body_template=:body,
                    updated_by=:actor, updated_at=CURRENT_TIMESTAMP(3) WHERE id=:id AND project_id=:project
                """).param("event", eventCode).param("subject", subject).param("body", body).param("actor", actorId)
                .param("id", id).param("project", projectId).update();
    }

    public void delete(String id, String projectId) {
        jdbc.sql("DELETE FROM notification_template WHERE id = :id AND project_id = :projectId")
                .param("id", id).param("projectId", projectId).update();
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private NotificationTemplate template(java.sql.ResultSet rs) throws java.sql.SQLException {
        String secret = rs.getString("signing_secret_ciphertext");
        return new NotificationTemplate(rs.getString("id"), rs.getString("organization_id"), rs.getString("project_id"),
                rs.getString("name"), rs.getString("event_code"), rs.getString("channel"),
                rs.getString("subject_template"), rs.getString("body_template"), rs.getBoolean("enabled"),
                rs.getString("target_url"), secret != null && !secret.isBlank(), rs.getString("updated_by"),
                instant(rs.getTimestamp("updated_at")));
    }

    public record StoredNotificationTemplate(NotificationTemplate template, String signingSecretCiphertext) {
    }
}
