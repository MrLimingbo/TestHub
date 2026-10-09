package io.testhub.platform.performance;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Definition sync never changes per-end readiness or submits executions. */
@Service
public class PerformanceSyncService {
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int SNAPSHOT_BYTES = 3 * 1024 * 1024;
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private final AccessService access;
    private final PerformanceTestPlanRepository repository;
    private final PerformanceTestPlanService plans;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public PerformanceSyncService(AccessService access, PerformanceTestPlanRepository repository,
                                  PerformanceTestPlanService plans, AuditService audit, JdbcClient jdbc, ObjectMapper json) {
        this.access = access; this.repository = repository; this.plans = plans;
        this.audit = audit; this.jdbc = jdbc; this.json = json;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot snapshot(AuthenticatedPrincipal actor, String projectId, String expectedUserId) {
        access.requireProjectParameterWrite(actor, projectId, WRITERS);
        requireActor(actor, expectedUserId);
        return read(projectId, actor.userId());
    }

    private Snapshot read(String projectId, String actorId) {
        if (repository.countByProject(projectId) > 100) throw invalid("每项目最多同步 100 个压测计划");
        long storedBytes = jdbc.sql("""
                SELECT COALESCE(SUM(COALESCE(OCTET_LENGTH(request_body),0)+COALESCE(OCTET_LENGTH(data_set_csv),0)
                    +OCTET_LENGTH(request_headers)+OCTET_LENGTH(query_parameters)),0)
                FROM performance_test_thread_group WHERE project_id=:project
                """).param("project", projectId).query(Long.class).single();
        if (storedBytes > SNAPSHOT_BYTES) throw invalid("性能计划超过 3 MiB 同步范围，请缩减资料");
        var rows = new ArrayList<RevisionRow>();
        var items = new ArrayList<Item>();
        for (var plan : repository.findByProject(projectId).stream().sorted(Comparator.comparing(PerformanceTestPlan::id)).toList()) {
            var detail = repository.findDetail(projectId, plan.id()).orElseThrow();
            var item = new Item(detail.name(), detail.description(), detail.threadGroups().stream().map(group ->
                    new PerformanceTestPlanService.ThreadGroupInput(group.name(), group.targetUrl(), group.loadModel(),
                            group.threadCount(), group.rampUpSeconds(), group.stepCount(), group.stepIntervalSeconds(),
                            group.durationSeconds(), group.targetTps(), group.p95ThresholdMillis(), group.errorThresholdPercent(),
                            group.requestMethod(), group.requestHeaders(), group.queryParameters(), group.requestBody(),
                            group.expectedStatusCode(), group.responseContains(), group.dataSetCsv(), group.rendezvousUsers())).toList());
            items.add(item);
            rows.add(new RevisionRow(detail.id(), detail.version(), detail.status(), detail.updatedAt().toString(), item));
        }
        validateItems(items);
        long executions = jdbc.sql("SELECT COUNT(*) FROM performance_test_execution WHERE project_id=:project")
                .param("project", projectId).query(Long.class).single();
        var snapshot = new Snapshot(1, "PERFORMANCE_SYNC", projectId, actorId, hash(encoded(rows)),
                items.stream().sorted(Comparator.comparing(this::key)).toList(), executions);
        if (encoded(snapshot).length > SNAPSHOT_BYTES) throw invalid("性能计划超过 3 MiB 同步范围，请缩减资料");
        return snapshot;
    }

    public Request decode(byte[] body) {
        if (body == null || body.length == 0) throw invalid("性能同步请求不能为空");
        if (body.length > MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "性能同步请求不能超过 4 MiB。");
        try {
            return json.readerFor(Request.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(body);
        } catch (java.io.IOException error) { throw invalid("性能同步 JSON 格式不合法或包含不支持的字段"); }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt apply(AuthenticatedPrincipal actor, String projectId, Request request) {
        var project = access.requireProjectParameterWrite(actor, projectId, WRITERS);
        if (request == null) throw invalid("性能同步请求不能为空");
        requireActor(actor, request.expectedUserId());
        String requestKey = uuid(request.requestKey());
        if (request.expectedRevision() == null || !request.expectedRevision().matches("[a-f0-9]{64}")
                || request.operations() == null || request.operations().isEmpty() || request.operations().size() > 100)
            throw invalid("性能同步版本或操作数量无效");
        var ids = new HashSet<String>();
        var keys = new HashSet<String>();
        for (var operation : request.operations()) {
            if (operation == null || !ids.add(uuid(operation.operationId()))) throw invalid("同步操作标识重复或无效");
            validate(operation.item());
            if (!keys.add(key(operation.item()))) throw invalid("同一压测计划不能重复提交");
        }
        byte[] payload = encoded(request);
        if (payload.length > MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "性能同步请求不能超过 4 MiB。");
        String payloadHash = hash(payload);
        repository.lockProject(projectId);
        var prior = jdbc.sql("""
                SELECT payload_sha256,result_json FROM performance_sync_receipt
                WHERE project_id=:project AND requested_by=:user AND request_key=:key
                """).param("project", projectId).param("user", actor.userId()).param("key", requestKey)
                .query((rs, n) -> Map.entry(rs.getString(1), rs.getString(2))).optional();
        if (prior.isPresent()) {
            if (!payloadHash.equals(prior.get().getKey())) throw conflict("同步请求标识已用于其他性能计划");
            try { return json.readValue(prior.get().getValue(), Receipt.class); }
            catch (java.io.IOException error) { throw new IllegalStateException("性能同步回执损坏", error); }
        }
        var current = read(projectId, actor.userId());
        if (!current.revision().equals(request.expectedRevision())) throw conflict("目标性能计划已变化，请重新同步并核对冲突");
        var merged = new LinkedHashMap<String, Item>();
        current.items().forEach(item -> merged.put(key(item), item));
        for (var operation : request.operations()) {
            var item = operation.item();
            var old = merged.get(key(item));
            if (old != null && !old.name().equals(item.name())) throw conflict("压测计划名称大小写不同，请先在两端统一名称");
            merged.put(key(item), item);
        }
        validateItems(new ArrayList<>(merged.values()));
        if (encoded(merged.values()).length > SNAPSHOT_BYTES) throw invalid("合并后的性能计划超过 3 MiB 同步范围");
        var existing = new HashMap<String, PerformanceTestPlan>();
        repository.findByProject(projectId).forEach(plan -> existing.put(plan.name().toLowerCase(Locale.ROOT), plan));
        try {
            for (var operation : request.operations()) {
                var item = operation.item();
                var old = existing.get(key(item));
                if (old == null) plans.create(actor, projectId, input(item, "DRAFT", null));
                else plans.update(actor, projectId, old.id(), input(item, old.status(), old.version()));
            }
            read(projectId, actor.userId());
            var receipt = new Receipt(requestKey, request.operations().stream()
                    .map(operation -> new Result(operation.operationId(), operation.item())).toList());
            jdbc.sql("""
                    INSERT INTO performance_sync_receipt(id,organization_id,project_id,requested_by,request_key,payload_sha256,result_json)
                    VALUES(:id,:org,:project,:user,:key,:hash,:result)
                    """).param("id", UlidCreator.getUlid().toString()).param("org", project.organizationId())
                    .param("project", projectId).param("user", actor.userId()).param("key", requestKey)
                    .param("hash", payloadHash).param("result", text(receipt)).update();
            audit.record(actor, project.organizationId(), projectId, "PERFORMANCE_SYNCED", "PERFORMANCE_SYNC", requestKey,
                    Map.of("plans", request.operations().size()));
            return receipt;
        } catch (DataIntegrityViolationException error) { throw conflict("性能计划名称与目标数据库的唯一约束冲突，请统一名称；整批未写入"); }
    }

    private PerformanceTestPlanService.PlanInput input(Item item, String status, Integer version) {
        return new PerformanceTestPlanService.PlanInput(item.name(), item.description(), status, item.threadGroups(), version);
    }
    private void validateItems(List<Item> items) {
        if (items.size() > 100) throw invalid("每项目最多同步 100 个压测计划");
        var keys = new HashSet<String>();
        for (var item : items) {
            validate(item);
            if (!keys.add(key(item))) throw invalid("压测计划存在同名或大小写重复，请先统一名称");
        }
    }
    private void validate(Item item) {
        if (item == null || !input(item, "DRAFT", null).equals(plans.normalizeDefinition(item.name(), item.description(), item.threadGroups())))
            throw invalid("性能计划字段不合法或尚未规范化");
    }
    private String key(Item item) { return item.name().toLowerCase(Locale.ROOT); }
    private void requireActor(AuthenticatedPrincipal actor, String expected) {
        if (!actor.userId().equals(expected)) throw conflict("登录账号已变化，同步已停止");
    }
    private String uuid(String value) {
        if (value == null || !value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
            throw invalid("同步请求标识必须是 UUID");
        return UUID.fromString(value).toString();
    }
    private byte[] encoded(Object value) {
        try { return json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(value); }
        catch (java.io.IOException error) { throw new IllegalStateException("性能同步序列化失败", error); }
    }
    private String text(Object value) { return new String(encoded(value), StandardCharsets.UTF_8); }
    private String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    public record Item(String name, String description, List<PerformanceTestPlanService.ThreadGroupInput> threadGroups) { }
    public record Snapshot(int version, String scope, String projectId, String actorId, String revision, List<Item> items, long excludedExecutions) { }
    public record Operation(String operationId, Item item) { }
    public record Request(String requestKey, String expectedUserId, String expectedRevision, List<Operation> operations) { }
    public record Result(String operationId, Item item) { }
    public record Receipt(String requestKey, List<Result> results) { }
    private record RevisionRow(String id, int version, String status, String updatedAt, Item item) { }
}
