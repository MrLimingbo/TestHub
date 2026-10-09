package io.testhub.platform.performance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Explicit copy of editable plans. Imports stay in draft and never enqueue load. */
@Service
public class PerformanceTransferService {
    public static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private final AccessService access;
    private final PerformanceTestPlanRepository repository;
    private final PerformanceTestPlanService plans;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public PerformanceTransferService(AccessService access, PerformanceTestPlanRepository repository,
                                      PerformanceTestPlanService plans, AuditService audit, JdbcClient jdbc, ObjectMapper json) {
        this.access = access; this.repository = repository; this.plans = plans;
        this.audit = audit; this.jdbc = jdbc; this.json = json;
    }

    public Map<String, Object> capabilities(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectParameterWrite(principal, projectId, WRITERS);
        return Map.of("version", 1, "scope", "PERFORMANCE", "maxBytes", MAX_BYTES,
                "includes", List.of("plans", "threadGroups", "inlineCsv"));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot export(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectParameterWrite(principal, projectId, WRITERS);
        List<Plan> items = repository.findByProject(projectId).stream().map(plan -> {
            var detail = repository.findDetail(projectId, plan.id()).orElseThrow(() -> invalid("压测计划不存在"));
            return new Plan(detail.id(), detail.name(), detail.description(), detail.status(), detail.version(),
                    detail.threadGroups().stream().map(group -> new PerformanceTestPlanService.ThreadGroupInput(
                            group.name(), group.targetUrl(), group.loadModel(), group.threadCount(), group.rampUpSeconds(),
                            group.stepCount(), group.stepIntervalSeconds(), group.durationSeconds(), group.targetTps(),
                            group.p95ThresholdMillis(), group.errorThresholdPercent(), group.requestMethod(),
                            group.requestHeaders(), group.queryParameters(), group.requestBody(), group.expectedStatusCode(),
                            group.responseContains(), group.dataSetCsv(), group.rendezvousUsers())).toList());
        }).toList();
        Snapshot snapshot = new Snapshot(1, projectId, items);
        validate(snapshot); encoded(snapshot);
        return snapshot;
    }

    public ImportRequest decode(byte[] body) {
        if (body == null || body.length == 0 || body.length > MAX_BYTES) throw invalid("性能迁移请求不能为空或超过 32 MiB");
        try {
            ImportRequest request = json.readerFor(ImportRequest.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(body);
            if (request == null || request.snapshot() == null) throw invalid("性能迁移资料不能为空");
            return request;
        } catch (java.io.IOException error) { throw invalid("性能迁移请求 JSON 格式不合法或包含不支持的字段"); }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt transfer(AuthenticatedPrincipal principal, String projectId, ImportRequest request) {
        var project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
        if (request == null || request.requestKey() == null ||
                !request.requestKey().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw invalid("requestKey 必须是 UUID");
        String requestKey;
        try { requestKey = UUID.fromString(request.requestKey()).toString(); }
        catch (IllegalArgumentException error) { throw invalid("requestKey 必须是 UUID"); }
        if (!principal.userId().equals(request.expectedUserId())) throw conflict("登录账号已变化，请重新预览迁移");
        Snapshot source = request.snapshot();
        String hash = sha256(encoded(source));
        jdbc.sql("SELECT id FROM project WHERE id = :projectId FOR UPDATE").param("projectId", projectId).query(String.class).single();
        var previous = jdbc.sql("""
                SELECT payload_sha256, result_json FROM performance_transfer_receipt
                WHERE project_id = :projectId AND requested_by = :userId AND request_key = :requestKey FOR UPDATE
                """).param("projectId", projectId).param("userId", principal.userId()).param("requestKey", requestKey)
                .query((rs, row) -> Map.entry(rs.getString(1), rs.getString(2))).optional();
        if (previous.isPresent()) {
            if (!previous.get().getKey().equals(hash)) throw conflict("该 requestKey 已用于其他迁移资料");
            try { return json.readValue(previous.get().getValue(), Receipt.class); }
            catch (JsonProcessingException error) { throw new IllegalStateException("性能迁移回执损坏", error); }
        }
        validate(source);
        if (source.plans().isEmpty()) throw invalid("本地项目没有可迁移的压测计划");
        var existing = repository.findByProject(projectId);
        if (existing.size() + source.plans().size() > 100) throw conflict("目标项目压测计划总数将超过 100 个");
        Set<String> names = new HashSet<>();
        for (var item : existing) names.add(item.name());
        for (Plan item : source.plans()) if (!names.add(item.name().trim())) throw conflict("目标项目存在同名压测计划：" + item.name().trim());
        int groups = 0, csv = 0;
        try {
            for (Plan item : source.plans()) {
                var created = plans.create(principal, projectId, new PerformanceTestPlanService.PlanInput(
                        item.name(), item.description(), "DRAFT", item.threadGroups(), null));
                jdbc.sql("UPDATE performance_test_plan SET version = :version WHERE project_id = :projectId AND id = :id")
                        .param("version", item.version()).param("projectId", projectId).param("id", created.id()).update();
                groups += item.threadGroups().size();
                csv += (int) item.threadGroups().stream().filter(group -> group.dataSetCsv() != null && !group.dataSetCsv().isBlank()).count();
            }
            Receipt receipt = new Receipt(UlidCreator.getUlid().toString(), source.plans().size(), groups, csv);
            jdbc.sql("""
                    INSERT INTO performance_transfer_receipt
                        (id, organization_id, project_id, requested_by, request_key, payload_sha256, result_json)
                    VALUES (:id, :organizationId, :projectId, :userId, :requestKey, :hash, :result)
                    """).param("id", receipt.id()).param("organizationId", project.organizationId()).param("projectId", projectId)
                    .param("userId", principal.userId()).param("requestKey", requestKey).param("hash", hash)
                    .param("result", new String(encoded(receipt), StandardCharsets.UTF_8)).update();
            audit.record(principal, project.organizationId(), projectId, "PERFORMANCE_TRANSFERRED", "PERFORMANCE_TRANSFER",
                    receipt.id(), Map.of("draftPlans", receipt.plans(), "threadGroups", groups, "csvDataSets", csv));
            return receipt;
        } catch (DataIntegrityViolationException error) { throw conflict("压测资料与目标项目冲突，未写入任何迁移数据"); }
    }

    private void validate(Snapshot source) {
        if (source == null || source.version() != 1 || !validId(source.sourceProjectId()) || source.plans() == null
                || source.plans().size() > 100 || source.plans().stream().anyMatch(java.util.Objects::isNull))
            throw invalid("性能迁移协议、来源项目或计划数量无效");
        Set<String> ids = new HashSet<>(), names = new HashSet<>();
        for (Plan plan : source.plans()) {
            if (!validId(plan.id()) || !ids.add(plan.id()) || plan.version() < 1 || plan.version() > 1_000_000)
                throw invalid("压测计划标识或版本无效");
            if (plan.name() == null || plan.name().isBlank() || plan.name().trim().length() > 160 || !names.add(plan.name().trim()))
                throw invalid("压测计划名称无效或重复");
            if (!Set.of("DRAFT", "READY", "ARCHIVED").contains(plan.status() == null ? "" : plan.status()))
                throw invalid("压测计划状态无效");
            if (plan.threadGroups() == null || plan.threadGroups().isEmpty() || plan.threadGroups().size() > 10
                    || plan.threadGroups().stream().anyMatch(java.util.Objects::isNull)) throw invalid("压测线程组数量无效");
        }
    }

    private boolean validId(String id) { return id != null && id.matches("[A-Z0-9]{26}"); }
    private byte[] encoded(Object value) {
        try {
            byte[] bytes = json.writeValueAsBytes(value);
            if (bytes.length > MAX_BYTES) throw invalid("性能迁移资料不能超过 32 MiB");
            return bytes;
        } catch (JsonProcessingException error) { throw invalid("性能迁移资料无法序列化"); }
    }
    private String sha256(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }

    public record Snapshot(int version, String sourceProjectId, List<Plan> plans) { }
    public record Plan(String id, String name, String description, String status, int version,
                       List<PerformanceTestPlanService.ThreadGroupInput> threadGroups) { }
    public record ImportRequest(String requestKey, String expectedUserId, Snapshot snapshot) { }
    public record Receipt(String id, int plans, int threadGroups, int csvDataSets) { }
}
