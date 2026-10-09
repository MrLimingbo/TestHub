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

/** Definition changes preserve local delivery configuration; new templates cannot deliver. */
@Service
public class NotificationSyncService {
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD");
    private static final Set<String> CHANNELS = Set.of("EMAIL", "WEBHOOK", "WECHAT", "DINGTALK");
    private final AccessService access;
    private final NotificationTemplateRepository templates;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    public NotificationSyncService(AccessService access, NotificationTemplateRepository templates,
            AuditService audit, JdbcClient jdbc, ObjectMapper json) {
        this.access = access; this.templates = templates; this.audit = audit; this.jdbc = jdbc; this.json = json;
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot snapshot(AuthenticatedPrincipal actor, String projectId, String expectedUserId) {
        access.requireProjectParameterWrite(actor, projectId, WRITERS); requireActor(actor, expectedUserId);
        return read(projectId, actor.userId());
    }
    private Snapshot read(String projectId, String actorId) {
        int count = jdbc.sql("SELECT COUNT(*) FROM notification_template WHERE project_id=:project")
                .param("project", projectId).query(Integer.class).single();
        if (count > 1000) throw invalid("通知同步最多支持 1000 个模板");
        // No URL or ciphertext is selected into the sync snapshot or its revision.
        var rows = jdbc.sql("""
                SELECT id,name,event_code,channel,subject_template,body_template,enabled,updated_at,
                    CASE WHEN target_url IS NOT NULL AND target_url<>'' THEN 1 ELSE 0 END AS has_target,
                    CASE WHEN signing_secret_ciphertext IS NOT NULL AND signing_secret_ciphertext<>'' THEN 1 ELSE 0 END AS has_secret
                FROM notification_template WHERE project_id=:project ORDER BY id
                """).param("project", projectId).query((rs,n) -> new RevisionRow(rs.getString("id"),
                    new Item(rs.getString("name"),rs.getString("event_code"),rs.getString("channel"),
                        rs.getString("subject_template"),rs.getString("body_template")),rs.getBoolean("enabled"),
                    rs.getString("updated_at"),rs.getBoolean("has_target"),rs.getBoolean("has_secret"))).list();
        var items = rows.stream().map(RevisionRow::item).sorted(Comparator.comparing(i -> key(i.name()))).toList();
        var keys = new HashSet<String>();
        for (var item : items) { validate(item); if (!keys.add(key(item.name()))) throw invalid("通知模板存在同名或大小写重复，请先统一名称"); }
        var result = new Snapshot(1,"NOTIFICATION_TEMPLATES_SYNC",projectId,actorId,hash(encoded(rows)),items,
                (int)rows.stream().filter(RevisionRow::hasTarget).count(),(int)rows.stream().filter(RevisionRow::hasSecret).count());
        if (encoded(result).length > 3 * 1024 * 1024) throw invalid("通知模板超过 3 MiB 同步范围，请缩减资料");
        return result;
    }
    public Request decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw invalid("通知同步请求不能为空");
        if (bytes.length > MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"通知同步请求不能超过 4 MiB。");
        try { return json.readerFor(Request.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(bytes); }
        catch (java.io.IOException error) { throw invalid("通知同步 JSON 格式不合法或包含不支持的字段"); }
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt apply(AuthenticatedPrincipal actor, String projectId, Request request) {
        var project = access.requireProjectParameterWrite(actor,projectId,WRITERS);
        if (request == null) throw invalid("通知同步请求不能为空");
        requireActor(actor,request.expectedUserId()); String requestKey = uuid(request.requestKey());
        if (request.expectedRevision() == null || !request.expectedRevision().matches("[a-f0-9]{64}")
                || request.operations() == null || request.operations().isEmpty() || request.operations().size() > 1000) throw invalid("通知同步版本或操作数量无效");
        var ids = new HashSet<String>(); var keys = new HashSet<String>();
        for (var op : request.operations()) {
            if (op == null || !ids.add(uuid(op.operationId()))) throw invalid("同步操作标识重复或无效");
            validate(op.item()); if (!keys.add(key(op.item().name()))) throw invalid("同一通知模板不能重复提交");
        }
        byte[] payload = encoded(request);
        if (payload.length > MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"通知同步请求不能超过 4 MiB。");
        String payloadHash = hash(payload);
        templates.lockProject(projectId);
        var prior = jdbc.sql("SELECT payload_sha256,result_json FROM notification_sync_receipt WHERE project_id=:project AND requested_by=:user AND request_key=:key")
                .param("project",projectId).param("user",actor.userId()).param("key",requestKey)
                .query((rs,n) -> Map.entry(rs.getString(1),rs.getString(2))).optional();
        if (prior.isPresent()) {
            if (!payloadHash.equals(prior.get().getKey())) throw conflict("同步请求标识已用于其他通知内容");
            try { return json.readValue(prior.get().getValue(),Receipt.class); }
            catch (java.io.IOException error) { throw new IllegalStateException("通知同步回执损坏",error); }
        }
        var current = read(projectId,actor.userId());
        if (!request.expectedRevision().equals(current.revision())) throw conflict("目标通知模板已变化，请重新同步并核对冲突");
        var existing = new HashMap<String,Item>(); current.items().forEach(i -> existing.put(key(i.name()),i));
        for (var op : request.operations()) {
            var old = existing.get(key(op.item().name()));
            if (old != null && !old.name().equals(op.item().name())) throw conflict("模板名称大小写不同，请先在两端统一名称");
            if (old != null && !old.channel().equals(op.item().channel())) throw conflict("模板通道不同，请先在两端手动统一；同步不会切换投递通道");
            existing.put(key(op.item().name()),op.item());
        }
        if (existing.size() > 1000) throw invalid("同步后将超过 1000 个通知模板");
        // The project lock also protects the existing CRUD and one-time transfer writers.
        var stored = new HashMap<String,String>();
        jdbc.sql("SELECT id,name FROM notification_template WHERE project_id=:project").param("project",projectId)
                .query((rs,n) -> Map.entry(key(rs.getString("name")),rs.getString("id")))
                .list().forEach(entry -> stored.put(entry.getKey(),entry.getValue()));
        for (var op : request.operations()) {
            var item = op.item(); var old = stored.get(key(item.name()));
            if (old == null) templates.insert(UlidCreator.getUlid().toString(),project.organizationId(),projectId,
                    item.name(),item.eventCode(),item.channel(),item.subjectTemplate(),item.bodyTemplate(),null,null,false,actor.userId());
            else templates.updateDefinition(old,projectId,item.eventCode(),item.subjectTemplate(),item.bodyTemplate(),actor.userId());
        }
        read(projectId,actor.userId()); // Validate the final snapshot size before committing any definitions.
        var receipt = new Receipt(requestKey,request.operations().stream().map(op -> new Result(op.operationId(),op.item())).toList());
        jdbc.sql("INSERT INTO notification_sync_receipt(id,organization_id,project_id,requested_by,request_key,payload_sha256,result_json) VALUES(:id,:org,:project,:user,:key,:hash,:result)")
                .param("id",UlidCreator.getUlid().toString()).param("org",project.organizationId()).param("project",projectId)
                .param("user",actor.userId()).param("key",requestKey).param("hash",payloadHash)
                .param("result",new String(encoded(receipt),StandardCharsets.UTF_8)).update();
        audit.record(actor,project.organizationId(),projectId,"NOTIFICATION_TEMPLATES_SYNCED","NOTIFICATION_SYNC",requestKey,Map.of("operations",request.operations().size()));
        return receipt;
    }
    private void validate(Item i) {
        if (i == null || !text(i.name(),120) || !text(i.subjectTemplate(),255) || !text(i.bodyTemplate(),20000)
                || i.eventCode() == null || !i.eventCode().matches("[a-z][a-z0-9_.-]{1,79}")
                || !CHANNELS.contains(i.channel() == null ? "" : i.channel())) throw invalid("通知模板字段不合法，请先调整名称、事件、通道或正文");
    }
    private boolean text(String s,int max) { return s != null && !s.isBlank() && s.length() <= max && s.equals(s.trim()); }
    private static String key(String name) { return name.toLowerCase(Locale.ROOT); }
    private void requireActor(AuthenticatedPrincipal actor,String expected) { if (!actor.userId().equals(expected)) throw conflict("登录账号已变化，同步已停止"); }
    private String uuid(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw invalid("同步请求标识必须是 UUID");
        return UUID.fromString(value).toString();
    }
    private byte[] encoded(Object value) {
        try { return json.writeValueAsBytes(value); }
        catch (java.io.IOException e) { throw new IllegalStateException("通知同步序列化失败",e); }
    }
    private String hash(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
    public record Item(String name,String eventCode,String channel,String subjectTemplate,String bodyTemplate) { }
    public record Snapshot(int version,String scope,String projectId,String actorId,String revision,List<Item> items,int excludedTargets,int excludedSecrets) { }
    public record Operation(String operationId,Item item) { }
    public record Request(String requestKey,String expectedUserId,String expectedRevision,List<Operation> operations) { }
    public record Result(String operationId,Item item) { }
    public record Receipt(String requestKey,List<Result> results) { }
    private record RevisionRow(String id,Item item,boolean enabled,String updatedAt,boolean hasTarget,boolean hasSecret) { }
}
