package io.testhub.platform.execution;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Synchronizes registrations only. The execution services and runner queues are never invoked. */
@Service
public class TaskSyncService {
    public static final int MAX_TASKS = 1000;
    public static final int MAX_BYTES = 1024 * 1024;
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private final AccessService access;
    private final ExecutionTaskService tasks;
    private final ExecutionTaskRepository repository;
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public TaskSyncService(AccessService access, ExecutionTaskService tasks, ExecutionTaskRepository repository, JdbcClient jdbc, ObjectMapper json) {
        this.access = access; this.tasks = tasks; this.repository = repository; this.jdbc = jdbc; this.json = json;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot snapshot(AuthenticatedPrincipal actor, String projectId, String expectedUserId) {
        access.requireProjectParameterWrite(actor, projectId, WRITERS);
        requireActor(actor, expectedUserId);
        var rows = repository.findForSync(projectId, MAX_TASKS + 1);
        if (rows.size() > MAX_TASKS) throw invalid("任务登记同步最多支持每个项目 1000 条，请先缩减登记数量");
        return new Snapshot(1, "TASK_REGISTRATION", projectId, actor.userId(), MAX_TASKS, rows.stream().map(TaskSyncService::item).toList());
    }

    public Request decode(byte[] body) {
        if (body == null || body.length == 0) throw invalid("任务同步请求不能为空");
        if (body.length > MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "任务同步请求不能超过 1 MiB。");
        try {
            return json.readerFor(Request.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(body);
        } catch (java.io.IOException error) { throw invalid("任务同步 JSON 格式不合法或包含不支持的字段"); }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt apply(AuthenticatedPrincipal actor, String projectId, Request request) {
        var project = access.requireProjectParameterWrite(actor, projectId, WRITERS);
        if (request == null) throw invalid("任务同步请求不能为空");
        requireActor(actor, request.expectedUserId());
        String key = uuid(request.requestKey());
        if (request.operations() == null || request.operations().isEmpty() || request.operations().size() > MAX_TASKS)
            throw invalid("任务同步操作数量应在 1 到 1000 之间");
        var operationIds = new HashSet<String>(); var taskIds = new HashSet<String>();
        for (var op : request.operations()) {
            if (op == null || !operationIds.add(uuid(op.operationId()))) throw invalid("同步操作标识重复或无效");
            if (op.taskId() == null) {
                if (op.expectedVersion() != null) throw invalid("新任务不能包含目标版本");
            } else if (!op.taskId().matches("[A-Z0-9]{26}") || !taskIds.add(op.taskId()) || op.expectedVersion() == null || op.expectedVersion() < 1)
                throw invalid("更新任务必须包含唯一标识和有效目标版本");
            if (op.taskType() == null || op.status() == null || !ExecutionTaskService.TYPES.contains(op.taskType()) || !ExecutionTaskService.STATUSES.contains(op.status())
                    || op.name() == null || op.name().isBlank() || op.name().length() > 160 || !op.name().equals(op.name().trim())
                    || op.progress() < 0 || op.progress() > 100 || (op.status().equals("SUCCEEDED") && op.progress() != 100)
                    || (op.status().equals("QUEUED") && op.progress() != 0)) throw invalid("同步任务名称、类型、状态或进度无效");
        }
        String hash = hash(encoded(request));
        // Serializes idempotency receipts and inserts across clients without blocking ordinary task edits.
        jdbc.sql("SELECT id FROM project WHERE id=:id FOR UPDATE").param("id", projectId).query(String.class).single();
        var prior = jdbc.sql("SELECT payload_sha256,result_json FROM task_sync_receipt WHERE project_id=:project AND requested_by=:user AND request_key=:key")
                .param("project", projectId).param("user", actor.userId()).param("key", key)
                .query((rs, row) -> java.util.Map.entry(rs.getString(1), rs.getString(2))).optional();
        if (prior.isPresent()) {
            if (!hash.equals(prior.get().getKey())) throw conflict("同步请求标识已用于其他内容");
            try { return json.readValue(prior.get().getValue(), Receipt.class); }
            catch (java.io.IOException error) { throw new IllegalStateException("任务同步回执损坏", error); }
        }
        if (repository.findForSync(projectId, MAX_TASKS + 1).size() + request.operations().stream().filter(op -> op.taskId() == null).count() > MAX_TASKS)
            throw invalid("目标项目同步后将超过 1000 条任务登记");
        var results = new java.util.ArrayList<Result>();
        // Stable lock order avoids deadlocks between sync batches. All mutations roll back on any conflict.
        for (var op : request.operations().stream().filter(op -> op.taskId() != null).sorted(java.util.Comparator.comparing(Operation::taskId)).toList()) {
            var current = repository.lock(projectId, op.taskId()).orElseThrow(() -> conflict("目标任务已不存在，请核对两端资料"));
            if (current.version() != op.expectedVersion() || !current.taskType().equals(op.taskType())) throw conflict("目标任务已变化，请重新同步并核对冲突");
        }
        for (var op : request.operations()) {
            ExecutionTask task;
            if (op.taskId() == null) {
                task = tasks.create(actor, projectId, new ExecutionTaskService.TaskInput(op.taskType(), op.name()));
                if (!op.status().equals("QUEUED")) task = tasks.update(actor, projectId, task.id(), new ExecutionTaskService.TaskUpdate(op.name(), op.status(), op.progress(), task.version()));
            } else task = tasks.update(actor, projectId, op.taskId(), new ExecutionTaskService.TaskUpdate(op.name(), op.status(), op.progress(), op.expectedVersion()));
            results.add(new Result(op.operationId(), item(task)));
        }
        var receipt = new Receipt(key, List.copyOf(results));
        jdbc.sql("""
                INSERT INTO task_sync_receipt(id,organization_id,project_id,requested_by,request_key,payload_sha256,result_json)
                VALUES(:id,:org,:project,:user,:key,:hash,:result)
                """).param("id", UlidCreator.getUlid().toString()).param("org", project.organizationId()).param("project", projectId)
                .param("user", actor.userId()).param("key", key).param("hash", hash).param("result", encoded(receipt)).update();
        return receipt;
    }
    private static Item item(ExecutionTask t) { return new Item(t.id(), t.version(), t.taskType(), t.name(), t.status(), t.progress()); }
    private void requireActor(AuthenticatedPrincipal actor, String expected) { if (!actor.userId().equals(expected)) throw conflict("登录账号已变化，同步已停止"); }
    private String uuid(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw invalid("同步请求标识必须是 UUID");
        return UUID.fromString(value).toString();
    }
    private String encoded(Object value) {
        try { return json.writeValueAsString(value); } catch (java.io.IOException error) { throw new IllegalStateException("任务同步序列化失败", error); }
    }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    public record Item(String id, long version, String taskType, String name, String status, int progress) { }
    public record Snapshot(int version, String scope, String projectId, String actorId, int maxTasks, List<Item> tasks) { }
    public record Operation(String operationId, String taskId, Long expectedVersion, String taskType, String name, String status, int progress) { }
    public record Request(String requestKey, String expectedUserId, List<Operation> operations) { }
    public record Result(String operationId, Item task) { }
    public record Receipt(String requestKey, List<Result> results) { }
}
