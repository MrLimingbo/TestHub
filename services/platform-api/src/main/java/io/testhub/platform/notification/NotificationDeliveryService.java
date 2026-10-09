package io.testhub.platform.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ProjectSummary;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.reporting.QualityReport;
import io.testhub.platform.reporting.QualityReportSchedule;
import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.security.SecretCipher;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NotificationDeliveryService {
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD");
    private static final int MAX_ATTEMPTS = 5;
    private final NotificationDeliveryRepository deliveries;
    private final NotificationTemplateRepository templates;
    private final AccessService access;
    private final AuditService audit;
    private final SecretCipher cipher;
    private final WebhookNotificationTransport transport;
    private final ObjectMapper json;

    public NotificationDeliveryService(NotificationDeliveryRepository deliveries, NotificationTemplateRepository templates,
                                       AccessService access, AuditService audit, SecretCipher cipher,
                                       WebhookNotificationTransport transport, ObjectMapper json) {
        this.deliveries = deliveries;
        this.templates = templates;
        this.access = access;
        this.audit = audit;
        this.cipher = cipher;
        this.transport = transport;
        this.json = json;
    }

    @Transactional(noRollbackFor = RenderedSubjectTooLong.class)
    public int enqueueQualityReport(String scheduleRunId, QualityReportSchedule schedule, QualityReport report) {
        List<NotificationDeliveryRepository.StoredNotificationDelivery> prepared = new java.util.ArrayList<>();
        for (QualityReport.ProjectHealth project : report.projects()) {
            prepared.addAll(prepare(scheduleRunId, schedule, report, project, "quality.report.ready", "READY"));
            if (project.failedExecutions() > 0 || project.criticalDefectCount() > 0) {
                prepared.addAll(prepare(scheduleRunId, schedule, report, project, "quality.report.risk", "RISK"));
            }
        }
        // Validate every rendered subject before any queue row is inserted.
        int queued = 0;
        for (var stored : prepared) {
            try { deliveries.insert(stored); queued++; }
            catch (DataIntegrityViolationException exception) {
                var row = stored.delivery();
                if (!deliveries.alreadyQueued(row.scheduleRunId(), row.templateId(), row.eventCode())) throw exception;
            }
        }
        return queued;
    }

    public List<NotificationDelivery> list(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectRead(principal, projectId);
        return deliveries.findByProject(projectId, 100);
    }

    @Transactional
    public NotificationDelivery retry(AuthenticatedPrincipal principal, String projectId, String deliveryId) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
        if (!deliveries.resetForManualRetry(deliveryId, projectId, Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "仅失败或已完成的通知可手动重试");
        }
        NotificationDelivery delivery = deliveries.findById(deliveryId, projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "通知投递记录不存在"));
        audit.record(principal, project.organizationId(), projectId, "NOTIFICATION_DELIVERY_REQUEUED",
                "NOTIFICATION_DELIVERY", deliveryId, Map.of("eventCode", delivery.eventCode()));
        return delivery;
    }

    @Scheduled(fixedDelayString = "${testhub.notifications.dispatch-interval-ms:5000}")
    public void dispatchDue() {
        Instant now = Instant.now();
        for (NotificationDeliveryRepository.StoredNotificationDelivery pending : deliveries.findDue(now, 20)) {
            deliveries.claim(pending.delivery().id(), now).ifPresent(this::dispatchClaimed);
        }
    }

    private void dispatchClaimed(NotificationDeliveryRepository.StoredNotificationDelivery stored) {
        NotificationDelivery delivery = stored.delivery();
        try {
            String timestamp = String.valueOf(Instant.now().getEpochSecond());
            String secret = cipher.decrypt(stored.signingSecretCiphertext());
            String signature = signature(secret, timestamp + "\n" + stored.payload());
            WebhookNotificationTransport.Result result = transport.deliver(delivery.targetUrl(), Map.of(
                    "X-TestHub-Event", delivery.eventCode(),
                    "X-TestHub-Delivery-Id", delivery.id(),
                    "X-TestHub-Timestamp", timestamp,
                    "X-TestHub-Signature", "sha256=" + signature), stored.payload());
            if (result.statusCode() >= 200 && result.statusCode() < 300) {
                deliveries.succeed(delivery.id(), result.statusCode(), Instant.now());
            } else if (retryable(result.statusCode()) && delivery.attemptCount() < MAX_ATTEMPTS) {
                deliveries.retry(delivery.id(), result.statusCode(), "Webhook 返回 HTTP " + result.statusCode(), retryAt(delivery));
            } else {
                deliveries.fail(delivery.id(), result.statusCode(), "Webhook 返回 HTTP " + result.statusCode());
                failureAudit(delivery, result.statusCode());
            }
        } catch (Exception exception) {
            String error = transportError(exception);
            if (delivery.attemptCount() < MAX_ATTEMPTS && retryableException(exception)) {
                deliveries.retry(delivery.id(), null, error, retryAt(delivery));
            } else {
                deliveries.fail(delivery.id(), null, error);
                failureAudit(delivery, null);
            }
        }
    }

    private List<NotificationDeliveryRepository.StoredNotificationDelivery> prepare(String scheduleRunId, QualityReportSchedule schedule, QualityReport report,
                        QualityReport.ProjectHealth project, String eventCode, String status) {
        List<NotificationDeliveryRepository.StoredNotificationDelivery> prepared = new java.util.ArrayList<>();
        for (NotificationTemplateRepository.StoredNotificationTemplate stored : templates.findEnabledWebhookTemplates(project.projectId(), eventCode)) {
            NotificationTemplate template = stored.template();
            String id = UlidCreator.getUlid().toString();
            String subject = render(template.subjectTemplate(), eventCode, project, schedule.name(), status);
            if (subject.length() > 255) throw new RenderedSubjectTooLong();
            String message = render(template.bodyTemplate(), eventCode, project, schedule.name(), status);
            String payload = payload(schedule, report, project, eventCode, status, id, subject, message);
            NotificationDelivery delivery = new NotificationDelivery(id, scheduleRunId, template.id(), schedule.organizationId(),
                    project.projectId(), template.name(), eventCode, "WEBHOOK", template.targetUrl(),
                    subject, message, "PENDING", 0, Instant.now(),
                    null, null, null, Instant.now(), Instant.now());
            prepared.add(new NotificationDeliveryRepository.StoredNotificationDelivery(delivery,
                    stored.signingSecretCiphertext(), payload));
        }
        return prepared;
    }

    public static class RenderedSubjectTooLong extends ResponseStatusException {
        public RenderedSubjectTooLong() { super(HttpStatus.BAD_REQUEST, "渲染后的通知主题超过 255 字符，请缩短主题模板后重试。"); }
    }

    private String payload(QualityReportSchedule schedule, QualityReport report, QualityReport.ProjectHealth project,
                           String eventCode, String status, String deliveryId, String subject, String message) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("event", eventCode);
        value.put("deliveryId", deliveryId);
        value.put("status", status);
        value.put("subject", subject);
        value.put("message", message);
        value.put("schedule", Map.of("id", schedule.id(), "name", schedule.name()));
        value.put("range", Map.of("from", report.from().toString(), "to", report.to().toString()));
        value.put("summary", report.summary());
        value.put("project", project);
        value.put("reportUrl", "/reports?from=" + report.from() + "&to=" + report.to() + "&projectId=" + project.projectId());
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("无法序列化通知投递载荷", exception);
        }
    }

    private String render(String template, String eventCode, QualityReport.ProjectHealth project, String task, String status) {
        return template.replace("{{event}}", eventCode).replace("{{project}}", project.projectName())
                .replace("{{task}}", task).replace("{{status}}", status);
    }

    private boolean retryable(int status) { return status == 408 || status == 429 || status >= 500; }
    private boolean retryableException(Exception exception) { return !(exception instanceof ResponseStatusException); }

    private Instant retryAt(NotificationDelivery delivery) {
        long seconds = Math.min(300, 1L << Math.min(8, Math.max(1, delivery.attemptCount())));
        return Instant.now().plusSeconds(seconds);
    }

    private String signature(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("无法签名 Webhook 投递", exception);
        }
    }

    private String transportError(Exception exception) {
        if (exception instanceof ResponseStatusException status) return status.getReason();
        return "Webhook 投递失败：" + exception.getClass().getSimpleName();
    }

    private void failureAudit(NotificationDelivery delivery, Integer responseStatus) {
        audit.recordSystem(delivery.organizationId(), delivery.projectId(), "NOTIFICATION_DELIVERY_FAILED",
                "NOTIFICATION_DELIVERY", delivery.id(), Map.of("eventCode", delivery.eventCode(),
                        "attemptCount", delivery.attemptCount(), "responseStatus", responseStatus == null ? "" : responseStatus));
    }
}
