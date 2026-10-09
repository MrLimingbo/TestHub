package io.testhub.platform.notification;

import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ProjectSummary;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.security.SecretCipher;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NotificationTemplateService {
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD");
    private static final Set<String> CHANNELS = Set.of("EMAIL", "WEBHOOK", "WECHAT", "DINGTALK");
    private final NotificationTemplateRepository templates;
    private final AccessService access;
    private final AuditService audit;
    private final SecretCipher cipher;
    private final WebhookTargetPolicy targetPolicy;

    public NotificationTemplateService(NotificationTemplateRepository templates, AccessService access, AuditService audit,
                                       SecretCipher cipher, WebhookTargetPolicy targetPolicy) {
        this.templates = templates;
        this.access = access;
        this.audit = audit;
        this.cipher = cipher;
        this.targetPolicy = targetPolicy;
    }

    public List<NotificationTemplate> list(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectRead(principal, projectId);
        return templates.findByProject(projectId);
    }

    @Transactional
    public NotificationTemplate create(AuthenticatedPrincipal principal, String projectId, TemplateInput input) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
        templates.lockProject(projectId);
        validate(input);
        TemplateDelivery delivery = delivery(input, null, true);
        String id = UlidCreator.getUlid().toString();
        templates.insert(id, project.organizationId(), projectId, input.name().trim(), normalizeEvent(input.eventCode()),
                normalizeChannel(input.channel()), input.subjectTemplate().trim(), input.bodyTemplate().trim(), delivery.targetUrl(),
                delivery.signingSecretCiphertext(), input.enabled(), principal.userId());
        audit.record(principal, project.organizationId(), projectId, "NOTIFICATION_TEMPLATE_CREATED",
                "NOTIFICATION_TEMPLATE", id, java.util.Map.of("name", input.name().trim(), "channel", normalizeChannel(input.channel())));
        return templates.findByProject(projectId).stream().filter(template -> template.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public NotificationTemplate update(AuthenticatedPrincipal principal, String projectId, String templateId, TemplateInput input) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
        templates.lockProject(projectId);
        validate(input);
        NotificationTemplateRepository.StoredNotificationTemplate current = templates.findStored(templateId, projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "通知模板不存在"));
        TemplateDelivery delivery = delivery(input, current.signingSecretCiphertext(), false);
        templates.update(templateId, projectId, input.name().trim(), normalizeEvent(input.eventCode()), normalizeChannel(input.channel()),
                input.subjectTemplate().trim(), input.bodyTemplate().trim(), delivery.targetUrl(), delivery.signingSecretCiphertext(),
                input.enabled(), principal.userId());
        audit.record(principal, project.organizationId(), projectId, "NOTIFICATION_TEMPLATE_UPDATED",
                "NOTIFICATION_TEMPLATE", templateId, java.util.Map.of());
        return templates.findByProject(projectId).stream().filter(template -> template.id().equals(templateId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "通知模板不存在"));
    }

    @Transactional
    public void delete(AuthenticatedPrincipal principal, String projectId, String templateId) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
        templates.lockProject(projectId);
        templates.delete(templateId, projectId);
        audit.record(principal, project.organizationId(), projectId, "NOTIFICATION_TEMPLATE_DELETED",
                "NOTIFICATION_TEMPLATE", templateId, java.util.Map.of());
    }

    private void validate(TemplateInput input) {
        if (input == null || input.name() == null || input.name().isBlank() || input.name().length() > 120
                || input.eventCode() == null || input.subjectTemplate() == null || input.bodyTemplate() == null
                || input.subjectTemplate().isBlank() || input.bodyTemplate().isBlank()
                || input.subjectTemplate().length() > 255 || input.bodyTemplate().length() > 20_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "通知模板字段不合法");
        }
        normalizeEvent(input.eventCode());
        normalizeChannel(input.channel());
    }

    private TemplateDelivery delivery(TemplateInput input, String existingCiphertext, boolean creating) {
        if (!"WEBHOOK".equals(normalizeChannel(input.channel()))) return new TemplateDelivery(null, null);
        if (input.targetUrl() == null || input.targetUrl().isBlank()) {
            // Older generic Webhook templates remain valid but cannot be dispatched until a target and secret are configured.
            return new TemplateDelivery(null, null);
        }
        String targetUrl = targetPolicy.normalizeAndAssertAllowed(input.targetUrl());
        String providedSecret = input.signingSecret() == null ? "" : input.signingSecret().trim();
        if (providedSecret.isEmpty()) {
            if (creating || existingCiphertext == null || existingCiphertext.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Webhook 签名密钥不能为空");
            }
            return new TemplateDelivery(targetUrl, existingCiphertext);
        }
        if (providedSecret.length() > 512) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Webhook 签名密钥不能超过 512 字符");
        }
        return new TemplateDelivery(targetUrl, cipher.encrypt(providedSecret));
    }

    private String normalizeEvent(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z][a-z0-9_.-]{1,79}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "事件编码格式不合法");
        }
        return normalized;
    }

    private String normalizeChannel(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!CHANNELS.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的通知渠道");
        }
        return normalized;
    }

    public record TemplateInput(String name, String eventCode, String channel, String subjectTemplate,
                                String bodyTemplate, String targetUrl, String signingSecret, boolean enabled) {
    }

    private record TemplateDelivery(String targetUrl, String signingSecretCiphertext) {
    }
}
