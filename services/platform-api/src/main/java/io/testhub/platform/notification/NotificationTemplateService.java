package io.testhub.platform.notification;

import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ProjectSummary;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
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

    public NotificationTemplateService(NotificationTemplateRepository templates, AccessService access, AuditService audit) {
        this.templates = templates;
        this.access = access;
        this.audit = audit;
    }

    public List<NotificationTemplate> list(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectRead(principal, projectId);
        return templates.findByProject(projectId);
    }

    @Transactional
    public NotificationTemplate create(AuthenticatedPrincipal principal, String projectId, TemplateInput input) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
        validate(input);
        String id = UlidCreator.getUlid().toString();
        templates.insert(id, project.organizationId(), projectId, input.name().trim(), normalizeEvent(input.eventCode()),
                normalizeChannel(input.channel()), input.subjectTemplate().trim(), input.bodyTemplate().trim(), input.enabled(),
                principal.userId());
        audit.record(principal, project.organizationId(), projectId, "NOTIFICATION_TEMPLATE_CREATED",
                "NOTIFICATION_TEMPLATE", id, java.util.Map.of("name", input.name().trim(), "channel", normalizeChannel(input.channel())));
        return templates.findByProject(projectId).stream().filter(template -> template.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public NotificationTemplate update(AuthenticatedPrincipal principal, String projectId, String templateId, TemplateInput input) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
        validate(input);
        templates.update(templateId, projectId, input.name().trim(), normalizeEvent(input.eventCode()), normalizeChannel(input.channel()),
                input.subjectTemplate().trim(), input.bodyTemplate().trim(), input.enabled(), principal.userId());
        audit.record(principal, project.organizationId(), projectId, "NOTIFICATION_TEMPLATE_UPDATED",
                "NOTIFICATION_TEMPLATE", templateId, java.util.Map.of());
        return templates.findByProject(projectId).stream().filter(template -> template.id().equals(templateId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "通知模板不存在"));
    }

    @Transactional
    public void delete(AuthenticatedPrincipal principal, String projectId, String templateId) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
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
                                String bodyTemplate, boolean enabled) {
    }
}
