package io.testhub.platform.notification;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationDeliveryRepository {
    private final JdbcClient jdbc;
    public NotificationDeliveryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(StoredNotificationDelivery delivery) {
        jdbc.sql("""
                INSERT INTO notification_delivery
                    (id, schedule_run_id, template_id, organization_id, project_id, template_name, event_code, channel,
                     target_url, signing_secret_ciphertext, subject, message, payload, status, next_attempt_at)
                VALUES (:id, :scheduleRunId, :templateId, :organizationId, :projectId, :templateName, :eventCode, :channel,
                        :targetUrl, :signingSecretCiphertext, :subject, :message, CAST(:payload AS JSON), 'PENDING', :nextAttemptAt)
                """).param("id", delivery.delivery().id()).param("scheduleRunId", delivery.delivery().scheduleRunId())
                .param("templateId", delivery.delivery().templateId()).param("organizationId", delivery.delivery().organizationId())
                .param("projectId", delivery.delivery().projectId()).param("templateName", delivery.delivery().templateName())
                .param("eventCode", delivery.delivery().eventCode()).param("channel", delivery.delivery().channel())
                .param("targetUrl", delivery.delivery().targetUrl()).param("signingSecretCiphertext", delivery.signingSecretCiphertext())
                .param("subject", delivery.delivery().subject()).param("message", delivery.delivery().message())
                .param("payload", delivery.payload()).param("nextAttemptAt", Timestamp.from(delivery.delivery().nextAttemptAt())).update();
    }

    public List<NotificationDelivery> findByProject(String projectId, int limit) {
        return jdbc.sql(select() + " WHERE project_id = :projectId ORDER BY created_at DESC, id DESC LIMIT :limit")
                .param("projectId", projectId).param("limit", limit).query((rs, row) -> delivery(rs)).list();
    }

    public Optional<NotificationDelivery> findById(String id, String projectId) {
        return jdbc.sql(select() + " WHERE id=:id AND project_id=:project")
                .param("id", id).param("project", projectId).query((rs, row) -> delivery(rs)).optional();
    }

    public boolean alreadyQueued(String runId, String templateId, String eventCode) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM notification_delivery WHERE schedule_run_id=:run AND template_id=:template AND event_code=:event)")
                .param("run", runId).param("template", templateId).param("event", eventCode).query(Boolean.class).single();
    }

    public int failInterrupted() {
        return jdbc.sql("""
                UPDATE notification_delivery SET status='FAILED',
                    last_error='本机服务在投递中断后重启，结果无法确认。请核对接收端后决定是否重新投递。',
                    updated_at=CURRENT_TIMESTAMP(3)
                WHERE status='DELIVERING'
                """).update();
    }

    public List<StoredNotificationDelivery> findDue(Instant now, int limit) {
        return jdbc.sql(storedSelect() + " WHERE status IN ('PENDING', 'RETRYING') AND next_attempt_at <= :now "
                        + "ORDER BY next_attempt_at, created_at LIMIT :limit")
                .param("now", Timestamp.from(now)).param("limit", limit).query((rs, row) -> stored(rs)).list();
    }

    public Optional<StoredNotificationDelivery> claim(String deliveryId, Instant now) {
        int changed = jdbc.sql("""
                UPDATE notification_delivery
                SET status = 'DELIVERING', attempt_count = attempt_count + 1, updated_at = CURRENT_TIMESTAMP(3)
                WHERE id = :id AND status IN ('PENDING', 'RETRYING') AND next_attempt_at <= :now
                """).param("id", deliveryId).param("now", Timestamp.from(now)).update();
        if (changed == 0) return Optional.empty();
        return jdbc.sql(storedSelect() + " WHERE id = :id").param("id", deliveryId).query((rs, row) -> stored(rs)).optional();
    }

    public void succeed(String id, int responseStatus, Instant deliveredAt) {
        jdbc.sql("""
                UPDATE notification_delivery
                SET status = 'SUCCEEDED', delivered_at = :deliveredAt, response_status = :responseStatus,
                    next_attempt_at = :deliveredAt, last_error = NULL, updated_at = CURRENT_TIMESTAMP(3)
                WHERE id = :id
                """).param("id", id).param("responseStatus", responseStatus)
                .param("deliveredAt", Timestamp.from(deliveredAt)).update();
    }

    public void retry(String id, Integer responseStatus, String error, Instant nextAttemptAt) {
        jdbc.sql("""
                UPDATE notification_delivery
                SET status = 'RETRYING', response_status = :responseStatus, last_error = :error,
                    next_attempt_at = :nextAttemptAt, updated_at = CURRENT_TIMESTAMP(3)
                WHERE id = :id
                """).param("id", id).param("responseStatus", responseStatus).param("error", abbreviate(error))
                .param("nextAttemptAt", Timestamp.from(nextAttemptAt)).update();
    }

    public void fail(String id, Integer responseStatus, String error) {
        jdbc.sql("""
                UPDATE notification_delivery
                SET status = 'FAILED', response_status = :responseStatus, last_error = :error,
                    next_attempt_at = CURRENT_TIMESTAMP(3), updated_at = CURRENT_TIMESTAMP(3)
                WHERE id = :id
                """).param("id", id).param("responseStatus", responseStatus).param("error", abbreviate(error)).update();
    }

    public boolean resetForManualRetry(String id, String projectId, Instant now) {
        return jdbc.sql("""
                UPDATE notification_delivery
                SET status = 'PENDING', attempt_count = 0, next_attempt_at = :now, delivered_at = NULL,
                    response_status = NULL, last_error = NULL, updated_at = CURRENT_TIMESTAMP(3)
                WHERE id = :id AND project_id = :projectId AND status IN ('FAILED', 'SUCCEEDED')
                """).param("id", id).param("projectId", projectId).param("now", Timestamp.from(now)).update() > 0;
    }

    private String select() {
        return "SELECT id, schedule_run_id, template_id, organization_id, project_id, template_name, event_code, channel, "
                + "target_url, subject, message, status, attempt_count, next_attempt_at, delivered_at, response_status, "
                + "last_error, created_at, updated_at FROM notification_delivery";
    }

    private String storedSelect() {
        return "SELECT id, schedule_run_id, template_id, organization_id, project_id, template_name, event_code, channel, "
                + "target_url, signing_secret_ciphertext, subject, message, payload, status, attempt_count, next_attempt_at, "
                + "delivered_at, response_status, last_error, created_at, updated_at FROM notification_delivery";
    }

    private StoredNotificationDelivery stored(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new StoredNotificationDelivery(delivery(rs), rs.getString("signing_secret_ciphertext"), rs.getString("payload"));
    }

    private NotificationDelivery delivery(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new NotificationDelivery(rs.getString("id"), rs.getString("schedule_run_id"), rs.getString("template_id"),
                rs.getString("organization_id"), rs.getString("project_id"), rs.getString("template_name"),
                rs.getString("event_code"), rs.getString("channel"), rs.getString("target_url"), rs.getString("subject"),
                rs.getString("message"), rs.getString("status"), rs.getInt("attempt_count"), instant(rs.getTimestamp("next_attempt_at")),
                instant(rs.getTimestamp("delivered_at")), nullableInteger(rs, "response_status"), rs.getString("last_error"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
    }

    private Integer nullableInteger(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    private String abbreviate(String value) {
        if (value == null) return null;
        return value.length() <= 512 ? value : value.substring(0, 512);
    }

    public record StoredNotificationDelivery(NotificationDelivery delivery, String signingSecretCiphertext, String payload) {
    }
}
