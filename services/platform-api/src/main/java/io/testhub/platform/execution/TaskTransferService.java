package io.testhub.platform.execution;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Copies manual task registrations; no execution manifest or runner message is created. */
@Service
public class TaskTransferService {
    public static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private final AccessService access;
    private final ExecutionTaskRepository tasks;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    public TaskTransferService(AccessService access, ExecutionTaskRepository tasks, AuditService audit, JdbcClient jdbc, ObjectMapper json) {
        this.access = access; this.tasks = tasks; this.audit = audit; this.jdbc = jdbc; this.json = json;
    }
    public Map<String, Object> capabilities(AuthenticatedPrincipal actor, String projectId) {
        access.requireProjectParameterWrite(actor, projectId, WRITERS);
        return Map.of("version", 1, "scope", "TASK_REGISTRATION", "maxBytes", MAX_BYTES, "includes", List.of("tasks"));
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot export(AuthenticatedPrincipal actor, String projectId) {
        access.requireProjectParameterWrite(actor, projectId, WRITERS);
        var result = new Snapshot(1, projectId, tasks.findByProject(projectId).stream().map(t -> new Task(
                t.id(), t.taskType(), t.name(), t.status(), t.progress(), t.version(), t.createdAt(), t.updatedAt())).toList());
        validate(result); encoded(result); return result;
    }
    public ImportRequest decode(byte[] body) {
        if (body == null || body.length == 0 || body.length > MAX_BYTES) throw invalid("任务登记迁移请求不能为空或超过 32 MiB");
        try {
            ImportRequest request = json.readerFor(ImportRequest.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(body);
            if (request == null || request.snapshot() == null) throw invalid("任务登记资料不能为空");
            return request;
        } catch (java.io.IOException error) { throw invalid("任务登记迁移 JSON 格式不合法或包含不支持的字段"); }
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt transfer(AuthenticatedPrincipal actor, String projectId, ImportRequest request) {
        var project = access.requireProjectParameterWrite(actor, projectId, WRITERS);
        if (request == null || request.requestKey() == null || !request.requestKey().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw invalid("requestKey 必须是 UUID");
        if (!actor.userId().equals(request.expectedUserId())) throw conflict("登录账号已变化，请重新预览");
        String key = UUID.fromString(request.requestKey()).toString(), hash = hash(encoded(request.snapshot()));
        jdbc.sql("SELECT id FROM project WHERE id=:id FOR UPDATE").param("id", projectId).query(String.class).single();
        var prior = jdbc.sql("SELECT payload_sha256,result_json FROM task_transfer_receipt WHERE project_id=:project AND requested_by=:user AND request_key=:key FOR UPDATE")
                .param("project", projectId).param("user", actor.userId()).param("key", key)
                .query((rs, row) -> Map.entry(rs.getString(1), rs.getString(2))).optional();
        if (prior.isPresent()) {
            if (!hash.equals(prior.get().getKey())) throw conflict("该 requestKey 已用于其他任务登记资料");
            try { return json.readValue(prior.get().getValue(), Receipt.class); }
            catch (java.io.IOException error) { throw new IllegalStateException("任务登记迁移回执损坏", error); }
        }
        var snapshot = request.snapshot(); validate(snapshot);
        if (snapshot.tasks().isEmpty()) throw invalid("来源项目没有可迁移的任务登记");
        int cancelled = 0;
        for (var task : snapshot.tasks()) {
            boolean active = Set.of("QUEUED", "RUNNING").contains(task.status());
            if (active) cancelled++;
            jdbc.sql("""
                    INSERT INTO execution_task(id,organization_id,project_id,task_type,name,status,progress,requested_by,
                        source_task_id,source_status,source_version,source_created_at,source_updated_at)
                    VALUES(:id,:org,:project,:type,:name,:status,:progress,:user,:source,:sourceStatus,:sourceVersion,:created,:updated)
                    """).param("id", UlidCreator.getUlid().toString()).param("org", project.organizationId()).param("project", projectId)
                    .param("type", task.taskType()).param("name", task.name()).param("status", active ? "CANCELLED" : task.status())
                    .param("progress", task.progress()).param("user", actor.userId()).param("source", task.id())
                    .param("sourceStatus", task.status()).param("sourceVersion", task.version())
                    .param("created", Timestamp.from(task.createdAt())).param("updated", Timestamp.from(task.updatedAt())).update();
        }
        var result = new Receipt(UlidCreator.getUlid().toString(), snapshot.tasks().size(), cancelled);
        jdbc.sql("INSERT INTO task_transfer_receipt(id,organization_id,project_id,requested_by,request_key,payload_sha256,result_json) VALUES(:id,:org,:project,:user,:key,:hash,:result)")
                .param("id", result.id()).param("org", project.organizationId()).param("project", projectId).param("user", actor.userId())
                .param("key", key).param("hash", hash).param("result", new String(encoded(result), StandardCharsets.UTF_8)).update();
        audit.record(actor, project.organizationId(), projectId, "TASK_REGISTRATIONS_TRANSFERRED", "TASK_TRANSFER", result.id(),
                Map.of("tasks", result.tasks(), "cancelledActiveTasks", cancelled));
        return result;
    }
    private void validate(Snapshot value) {
        if (value == null || value.version() != 1 || !id(value.sourceProjectId()) || value.tasks() == null || value.tasks().size() > 1000)
            throw invalid("任务登记迁移协议或资料数量不合法");
        Set<String> ids = new HashSet<>();
        for (Task task : value.tasks()) {
            if (task == null || !id(task.id()) || !ids.add(task.id()) || task.taskType() == null || !ExecutionTaskService.TYPES.contains(task.taskType())
                    || task.name() == null || task.name().isBlank() || task.name().length() > 160
                    || task.status() == null || !ExecutionTaskService.STATUSES.contains(task.status()) || task.progress() == null || task.progress() < 0 || task.progress() > 100
                    || task.version() < 1 || !date(task.createdAt()) || !date(task.updatedAt())
                    || task.status().equals("QUEUED") && task.progress() != 0 || task.status().equals("SUCCEEDED") && task.progress() != 100)
                throw invalid("任务登记标识、类型、状态、进度、版本或时间不合法");
        }
    }
    private boolean date(Instant value) {
        return value != null && !value.isBefore(Instant.parse("1000-01-01T00:00:00Z"))
                && value.isBefore(Instant.parse("+10000-01-01T00:00:00Z"));
    }
    private boolean id(String value) { return value != null && value.matches("[0-9A-HJKMNP-TV-Z]{26}"); }
    private byte[] encoded(Object value) {
        try { byte[] result = json.writeValueAsBytes(value); if (result.length > MAX_BYTES) throw invalid("任务登记资料超过 32 MiB"); return result; }
        catch (java.io.IOException error) { throw invalid("任务登记资料无法编码"); }
    }
    private String hash(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    public record Task(String id, String taskType, String name, String status, Integer progress, long version, Instant createdAt, Instant updatedAt) { }
    public record Snapshot(int version, String sourceProjectId, List<Task> tasks) { }
    public record ImportRequest(String requestKey, String expectedUserId, Snapshot snapshot) { }
    public record Receipt(String id, int tasks, int cancelledActiveTasks) { }
}
