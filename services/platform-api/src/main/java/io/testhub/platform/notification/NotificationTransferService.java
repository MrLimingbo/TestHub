package io.testhub.platform.notification;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NotificationTransferService {
    public static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD");
    private static final Set<String> CHANNELS = Set.of("EMAIL", "WEBHOOK", "WECHAT", "DINGTALK");
    private final AccessService access;
    private final NotificationTemplateRepository templates;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    public NotificationTransferService(AccessService access, NotificationTemplateRepository templates,
            AuditService audit, JdbcClient jdbc, ObjectMapper json) {
        this.access = access; this.templates = templates; this.audit = audit; this.jdbc = jdbc; this.json = json;
    }
    public Map<String,Object> capabilities(AuthenticatedPrincipal actor, String projectId) {
        access.requireProjectParameterWrite(actor, projectId, WRITERS);
        return Map.of("version", 1, "scope", "NOTIFICATION_TEMPLATES", "maxBytes", MAX_BYTES);
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot export(AuthenticatedPrincipal actor, String projectId) {
        access.requireProjectParameterWrite(actor, projectId, WRITERS);
        var rows = templates.findByProject(projectId);
        var snapshot = new Snapshot(1, projectId, rows.stream().map(t -> new Template(t.id(), t.name(),
                t.eventCode(), t.channel(), t.subjectTemplate(), t.bodyTemplate(), t.enabled())).toList(),
                (int) rows.stream().filter(t -> t.targetUrl() != null && !t.targetUrl().isBlank()).count(),
                (int) rows.stream().filter(NotificationTemplate::signingSecretConfigured).count());
        validate(snapshot); encoded(snapshot); return snapshot;
    }
    public ImportRequest decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw invalid("通知迁移请求不能为空或超过 32 MiB");
        try {
            var value = json.readerFor(ImportRequest.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(bytes);
            if (!(value instanceof ImportRequest request) || request.snapshot() == null) throw invalid("通知模板快照不能为空");
            return request;
        } catch (java.io.IOException error) { throw invalid("通知迁移 JSON 格式不合法或包含不支持的字段"); }
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt transfer(AuthenticatedPrincipal actor, String projectId, ImportRequest request) {
        var project = access.requireProjectParameterWrite(actor, projectId, WRITERS);
        if (request == null || request.requestKey() == null || !request.requestKey().matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw invalid("requestKey 必须是 UUID");
        if (!actor.userId().equals(request.expectedUserId())) throw conflict("登录账号已变化，请重新预览");
        var snapshot = request.snapshot(); validate(snapshot);
        String key = UUID.fromString(request.requestKey()).toString(), hash = hash(encoded(snapshot));
        templates.lockProject(projectId);
        var prior = jdbc.sql("SELECT payload_sha256,result_json FROM notification_transfer_receipt WHERE project_id=:project AND requested_by=:user AND request_key=:key FOR UPDATE")
                .param("project", projectId).param("user", actor.userId()).param("key", key)
                .query((rs, row) -> Map.entry(rs.getString(1), rs.getString(2))).optional();
        if (prior.isPresent()) {
            if (!hash.equals(prior.get().getKey())) throw conflict("该 requestKey 已用于其他通知资料");
            try { return json.readValue(prior.get().getValue(), Receipt.class); }
            catch (java.io.IOException error) { throw new IllegalStateException("通知迁移回执损坏", error); }
        }
        if (snapshot.templates().isEmpty()) throw invalid("来源没有可迁移的通知模板");
        Set<String> names = new HashSet<>(templates.findByProject(projectId).stream().map(t -> nameKey(t.name())).toList());
        for (var input : snapshot.templates()) {
            if (!names.add(nameKey(input.name()))) throw conflict("目标存在同名通知模板，请调整名称后重新预览；不会覆盖目标");
            // Definitions alone never authorize delivery. Targets, credentials and queue history stay in the source.
            templates.insert(UlidCreator.getUlid().toString(), project.organizationId(), projectId, input.name().trim(),
                    input.eventCode(), input.channel(), input.subjectTemplate(), input.bodyTemplate(), null, null, false, actor.userId());
        }
        var receipt = new Receipt(UlidCreator.getUlid().toString(), snapshot.templates().size(), snapshot.excludedTargets(), snapshot.excludedSecrets());
        jdbc.sql("INSERT INTO notification_transfer_receipt(id,organization_id,project_id,requested_by,request_key,payload_sha256,result_json) VALUES(:id,:org,:project,:user,:key,:hash,:result)")
                .param("id", receipt.id()).param("org", project.organizationId()).param("project", projectId).param("user", actor.userId())
                .param("key", key).param("hash", hash).param("result", new String(encoded(receipt), StandardCharsets.UTF_8)).update();
        audit.record(actor, project.organizationId(), projectId, "NOTIFICATION_TEMPLATES_TRANSFERRED", "NOTIFICATION_TRANSFER", receipt.id(),
                Map.of("templates", receipt.templates(), "excludedTargets", receipt.excludedTargets(), "excludedSecrets", receipt.excludedSecrets()));
        return receipt;
    }
    private void validate(Snapshot value) {
        if (value == null || value.version() != 1 || !id(value.sourceProjectId()) || value.templates() == null || value.templates().size() > 1000
                || value.excludedTargets() == null || value.excludedSecrets() == null || value.excludedTargets() < 0 || value.excludedSecrets() < 0
                || value.excludedTargets() > value.templates().size() || value.excludedSecrets() > value.templates().size()) throw invalid("通知模板迁移协议或数量不合法");
        Set<String> ids = new HashSet<>(), names = new HashSet<>();
        for (var t : value.templates()) {
            if (t == null || !id(t.id()) || !ids.add(t.id()) || t.name() == null || t.name().isBlank() || t.name().length() > 120
                    || !names.add(nameKey(t.name())) || t.eventCode() == null || !t.eventCode().matches("[a-z][a-z0-9_.-]{1,79}")
                    || !CHANNELS.contains(t.channel() == null ? "" : t.channel()) || t.sourceEnabled() == null
                    || t.subjectTemplate() == null || t.subjectTemplate().isBlank() || t.subjectTemplate().length() > 255
                    || t.bodyTemplate() == null || t.bodyTemplate().isBlank() || t.bodyTemplate().length() > 20_000) throw invalid("通知模板字段、标识或唯一名称不合法；请先调整来源同名模板");
        }
    }
    private String nameKey(String name) { return name.trim().toLowerCase(Locale.ROOT); }
    private boolean id(String value) { return value != null && value.matches("[0-9A-HJKMNP-TV-Z]{26}"); }
    private byte[] encoded(Object value) {
        try { byte[] bytes = json.writeValueAsBytes(value); if (bytes.length > MAX_BYTES) throw invalid("通知资料超过 32 MiB"); return bytes; }
        catch (java.io.IOException error) { throw invalid("通知资料无法编码"); }
    }
    private String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    public record Template(String id, String name, String eventCode, String channel, String subjectTemplate, String bodyTemplate, Boolean sourceEnabled) { }
    public record Snapshot(int version, String sourceProjectId, List<Template> templates, Integer excludedTargets, Integer excludedSecrets) { }
    public record ImportRequest(String requestKey, String expectedUserId, Snapshot snapshot) { }
    public record Receipt(String id, int templates, int excludedTargets, int excludedSecrets) { }
}
