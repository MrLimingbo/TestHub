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

    public List<NotificationTemplate> findByProject(String projectId) {
        return jdbc.sql("""
                SELECT id, organization_id, project_id, name, event_code, channel, subject_template, body_template,
                       enabled, updated_by, updated_at
                FROM notification_template WHERE project_id = :projectId ORDER BY updated_at DESC, name
                """).param("projectId", projectId).query((rs, rowNumber) -> new NotificationTemplate(
                rs.getString("id"), rs.getString("organization_id"), rs.getString("project_id"), rs.getString("name"),
                rs.getString("event_code"), rs.getString("channel"), rs.getString("subject_template"),
                rs.getString("body_template"), rs.getBoolean("enabled"), rs.getString("updated_by"),
                instant(rs.getTimestamp("updated_at")))).list();
    }

    public void insert(String id, String organizationId, String projectId, String name, String eventCode, String channel,
                       String subjectTemplate, String bodyTemplate, boolean enabled, String updatedBy) {
        jdbc.sql("""
                INSERT INTO notification_template
                    (id, organization_id, project_id, name, event_code, channel, subject_template, body_template,
                     enabled, updated_by)
                VALUES (:id, :organizationId, :projectId, :name, :eventCode, :channel, :subjectTemplate, :bodyTemplate,
                        :enabled, :updatedBy)
                """).param("id", id).param("organizationId", organizationId).param("projectId", projectId)
                .param("name", name).param("eventCode", eventCode).param("channel", channel)
                .param("subjectTemplate", subjectTemplate).param("bodyTemplate", bodyTemplate)
                .param("enabled", enabled).param("updatedBy", updatedBy).update();
    }

    public void update(String id, String projectId, String name, String eventCode, String channel,
                       String subjectTemplate, String bodyTemplate, boolean enabled, String updatedBy) {
        jdbc.sql("""
                UPDATE notification_template
                SET name = :name, event_code = :eventCode, channel = :channel, subject_template = :subjectTemplate,
                    body_template = :bodyTemplate, enabled = :enabled, updated_by = :updatedBy,
                    updated_at = CURRENT_TIMESTAMP(3)
                WHERE id = :id AND project_id = :projectId
                """).param("id", id).param("projectId", projectId).param("name", name).param("eventCode", eventCode)
                .param("channel", channel).param("subjectTemplate", subjectTemplate).param("bodyTemplate", bodyTemplate)
                .param("enabled", enabled).param("updatedBy", updatedBy).update();
    }

    public void delete(String id, String projectId) {
        jdbc.sql("DELETE FROM notification_template WHERE id = :id AND project_id = :projectId")
                .param("id", id).param("projectId", projectId).update();
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
