package io.testhub.platform.apiautomation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** A read-only archive of value-free terminal reports. No imported row enters the Runner lifecycle. */
@Service
public class ApiAutomationHistorySyncService {
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_ITEMS = 1000;
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "CANCELLED", "REJECTED", "TIMED_OUT");
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private final AccessService access;
    private final ApiAutomationExecutionService executions;
    private final ApiAutomationTransferRepository projects;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    public ApiAutomationHistorySyncService(AccessService access, ApiAutomationExecutionService executions,
            ApiAutomationTransferRepository projects, AuditService audit, JdbcClient jdbc, ObjectMapper json) {
        this.access = access; this.executions = executions; this.projects = projects;
        this.audit = audit; this.jdbc = jdbc; this.json = json;
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Inventory inventory(AuthenticatedPrincipal actor, String project, String expectedUserId) {
        requireActor(actor, expectedUserId); access.requireProjectRead(actor, project);
        var entries = new ArrayList<Entry>(nativeEntries(project));
        entries.addAll(archivedEntries(project));
        entries.sort(java.util.Comparator.comparing(Entry::key));
        if (entries.size() > MAX_ITEMS) throw conflict("历史范围超过 1000 条，暂停同步；现有记录保留，不会截断复制");
        if (entries.stream().map(Entry::key).distinct().count() != entries.size()) throw conflict("历史来源标识重复，请先核对");
        return new Inventory(1, "API_AUTOMATION_HISTORY_SYNC", project, actor.userId(), hash(encoded(entries)), entries);
    }
    /** SQL fingerprints include late result arrivals without materializing all report bodies during every poll. */
    private List<Entry> nativeEntries(String project) { return nativeEntries(project, ""); }
    private List<Entry> nativeEntries(String project, String executionId) {
        return jdbc.sql("""
                SELECT e.id,e.scenario_name,e.status,e.requested_at,e.finished_at,e.updated_at,
                  m.checksum AS manifest_checksum,r.checksum AS runtime_checksum,
                  (SELECT COUNT(*) FROM api_automation_execution_http_result h WHERE h.execution_id=e.id) AS http_count,
                  (SELECT MAX(created_at) FROM api_automation_execution_http_result h WHERE h.execution_id=e.id) AS http_latest,
                  (SELECT COUNT(*) FROM api_automation_execution_step_result s WHERE s.execution_id=e.id) AS step_count,
                  (SELECT MAX(created_at) FROM api_automation_execution_step_result s WHERE s.execution_id=e.id) AS step_latest,
                  (SELECT COUNT(*) FROM api_automation_execution_script_result s WHERE s.execution_id=e.id) AS script_count,
                  (SELECT MAX(created_at) FROM api_automation_execution_script_result s WHERE s.execution_id=e.id) AS script_latest,
                  (SELECT COUNT(*) FROM api_automation_execution_log l WHERE l.execution_id=e.id AND l.source='RUNNER' AND l.code='FAILURE_CONTINUED') AS continued_count
                FROM api_automation_execution e
                LEFT JOIN api_automation_execution_manifest m ON m.execution_id=e.id
                LEFT JOIN api_automation_execution_runtime_snapshot r ON r.execution_id=e.id
                WHERE e.project_id=:p AND (:execution='' OR e.id=:execution) AND e.status IN ('SUCCEEDED','FAILED','CANCELLED','REJECTED','TIMED_OUT')
                ORDER BY e.id LIMIT 1001
                """).param("p", project).param("execution", executionId).query((rs, n) -> {
                    var fingerprint = new TreeMap<String, String>();
                    for (int column = 1; column <= rs.getMetaData().getColumnCount(); column++)
                        fingerprint.put(rs.getMetaData().getColumnLabel(column), rs.getString(column));
                    String id = rs.getString("id");
                    return new Entry(project + "." + id, hash(encoded(fingerprint)), true, rs.getString("scenario_name"),
                            rs.getString("status"), rs.getTimestamp("requested_at").toInstant(),
                            rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant());
                }).list();
    }
    private List<Entry> archivedEntries(String project) {
        return jdbc.sql("""
                SELECT source_project_id,source_execution_id,source_revision,scenario_name,status,requested_at,finished_at
                FROM api_automation_history_archive WHERE project_id=:p
                ORDER BY source_project_id,source_execution_id LIMIT 1001
                """).param("p", project).query((rs, n) -> new Entry(rs.getString(1) + "." + rs.getString(2), rs.getString(3), false,
                    rs.getString(4), rs.getString(5), rs.getTimestamp(6).toInstant(), rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant())).list();
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Item read(AuthenticatedPrincipal actor, String project, String expectedUserId, String key) {
        requireActor(actor, expectedUserId); access.requireProjectRead(actor, project); requireKey(key);
        if (key.startsWith(project + ".")) {
            var entry = nativeEntries(project, key.substring(27)).stream().findFirst()
                    .orElseThrow(() -> missing("已结束执行历史不存在"));
            var report = executions.report(actor, project, key.substring(27));
            var e = report.execution();
            var payload = new Payload(e.scenarioName(), e.scenarioVersion(), e.executionProfileVersion(), e.dataSetRowNumber(),
                    e.status(), e.statusCode(), e.requestedAt(), e.deadlineAt(), e.startedAt(), e.finishedAt(),
                    report.manifest(), report.runtime(), report.failureStrategy(), report.summary(), report.parallelGroups(),
                    report.httpResults().stream().map(r -> new HttpResult(r.stepId(), r.requestSequence(), r.attemptNumber(), r.method(), r.outcome(), r.statusCode(), r.durationMillis(), r.occurredAt())).toList(),
                    report.stepResults().stream().map(r -> new StepResult(r.stepId(), r.stepSequence(), r.stepType(), r.outcome(), r.observedStatusCode(), r.occurredAt())).toList(),
                    report.scriptResults().stream().map(r -> new ScriptResult(r.phase(), r.testCount(), r.passedCount(), r.failedCount(), r.occurredAt())).toList());
            var item = new Item(key, entry.revision(), hash(encoded(payload)), payload);
            validate(item); return item;
        }
        var stored = jdbc.sql("SELECT report_json,report_sha256 FROM api_automation_history_archive WHERE project_id=:p AND source_project_id=:sp AND source_execution_id=:se")
                .param("p", project).param("sp", key.substring(0, 26)).param("se", key.substring(27))
                .query((rs, n) -> Map.entry(rs.getString(1), rs.getString(2))).optional().orElseThrow(() -> missing("同步历史不存在"));
        var item = decoded(stored.getKey().getBytes(StandardCharsets.UTF_8), Item.class);
        validate(item);
        if (!item.key().equals(key) || !item.sha256().equals(stored.getValue())) throw new IllegalStateException("已保存的历史完整性校验失败");
        return item;
    }
    @Transactional(readOnly = true)
    public List<ArchivedSummary> recentArchives(AuthenticatedPrincipal actor, String project) {
        access.requireProjectRead(actor, project);
        return jdbc.sql("""
                SELECT source_project_id,source_execution_id,scenario_name,status,requested_at,started_at,finished_at,updated_at
                FROM api_automation_history_archive WHERE project_id=:p
                ORDER BY requested_at DESC,source_execution_id DESC,source_project_id DESC LIMIT 100
                """).param("p", project).query((rs, n) -> new ArchivedSummary(rs.getString(1) + "." + rs.getString(2),
                    rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant(),
                    rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(),
                    rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant(), rs.getTimestamp(8).toInstant())).list();
    }
    public Request decode(byte[] body) {
        if (body == null || body.length == 0) throw invalid("历史同步请求不能为空");
        if (body.length > MAX_BYTES) throw tooLarge();
        return decoded(body, Request.class);
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt apply(AuthenticatedPrincipal actor, String project, Request request) {
        var context = access.requireProjectParameterWrite(actor, project, WRITERS);
        if (request == null) throw invalid("历史同步请求不能为空");
        requireActor(actor, request.expectedUserId());
        if (!uuid(request.requestKey()) || !digest(request.expectedRevision()) || request.items() == null
                || request.items().isEmpty() || request.items().size() > 20) throw invalid("历史同步请求标识、修订或批次无效");
        var keys = new HashSet<String>();
        for (var item : request.items()) {
            validate(item);
            if (!keys.add(item.key())) throw invalid("同一来源历史不能重复提交");
            if (item.key().startsWith(project + ".")) throw conflict("本项目真实执行由原 Runner 管理，不能用同步报告改写");
        }
        byte[] payload = encoded(request); if (payload.length > MAX_BYTES) throw tooLarge();
        String digest = hash(payload); projects.lockProject(project);
        var prior = jdbc.sql("SELECT payload_sha256,result_json FROM api_automation_history_sync_receipt WHERE project_id=:p AND requested_by=:u AND request_key=:k")
                .param("p", project).param("u", actor.userId()).param("k", request.requestKey()).query((rs, n) -> Map.entry(rs.getString(1), rs.getString(2))).optional();
        if (prior.isPresent()) {
            if (!prior.get().getKey().equals(digest)) throw conflict("该请求标识已用于其他历史报告");
            return decoded(prior.get().getValue().getBytes(StandardCharsets.UTF_8), Receipt.class);
        }
        var before = inventory(actor, project, actor.userId());
        if (!before.revision().equals(request.expectedRevision())) throw conflict("目标历史已变化，请重新同步并核对");
        long added = keys.stream().filter(key -> before.entries().stream().noneMatch(entry -> entry.key().equals(key))).count();
        if (before.entries().size() + added > MAX_ITEMS) throw conflict("合并后历史超过 1000 条，现有记录保留");
        for (var item : request.items()) {
            var old = before.entries().stream().filter(entry -> entry.key().equals(item.key())).findFirst();
            if (old.isPresent() && old.get().revision().equals(item.revision())) {
                if (!read(actor, project, actor.userId(), item.key()).equals(item)) throw conflict("同一来源修订内容不同，请人工核对");
                continue;
            }
            var p = item.report();
            if (old.isEmpty()) jdbc.sql("""
                    INSERT INTO api_automation_history_archive
                      (id,organization_id,project_id,source_project_id,source_execution_id,source_revision,report_sha256,report_json,scenario_name,status,requested_at,started_at,finished_at,imported_by)
                    VALUES(:id,:o,:p,:sp,:se,:rev,:sha,:report,:name,:status,:requested,:started,:finished,:u)
                    """).param("id", UlidCreator.getUlid().toString()).param("o", context.organizationId()).param("p", project)
                    .param("sp", item.key().substring(0, 26)).param("se", item.key().substring(27)).param("rev", item.revision())
                    .param("sha", item.sha256()).param("report", text(item)).param("name", p.scenarioName()).param("status", p.status())
                    .param("requested", p.requestedAt()).param("started", p.startedAt()).param("finished", p.finishedAt()).param("u", actor.userId()).update();
            else jdbc.sql("""
                    UPDATE api_automation_history_archive SET source_revision=:rev,report_sha256=:sha,report_json=:report,
                      scenario_name=:name,status=:status,requested_at=:requested,started_at=:started,finished_at=:finished,updated_at=CURRENT_TIMESTAMP(3)
                    WHERE project_id=:p AND source_project_id=:sp AND source_execution_id=:se
                    """).param("p", project).param("sp", item.key().substring(0, 26)).param("se", item.key().substring(27))
                    .param("rev", item.revision()).param("sha", item.sha256()).param("report", text(item)).param("name", p.scenarioName())
                    .param("status", p.status()).param("requested", p.requestedAt()).param("started", p.startedAt()).param("finished", p.finishedAt()).update();
        }
        var receipt = new Receipt(request.requestKey(), request.items().stream().map(item -> new Result(item.key(), item.revision(), item.sha256())).toList());
        jdbc.sql("INSERT INTO api_automation_history_sync_receipt(id,organization_id,project_id,requested_by,request_key,payload_sha256,result_json) VALUES(:id,:o,:p,:u,:k,:h,:r)")
                .param("id", UlidCreator.getUlid().toString()).param("o", context.organizationId()).param("p", project).param("u", actor.userId())
                .param("k", request.requestKey()).param("h", digest).param("r", text(receipt)).update();
        audit.record(actor, context.organizationId(), project, "API_AUTOMATION_HISTORY_SYNCED", "API_AUTOMATION_HISTORY_SYNC", request.requestKey(), Map.of("reports", request.items().size()));
        return receipt;
    }
    private void validate(Item item) {
        if (item == null) throw invalid("历史报告不能为空");
        requireKey(item.key());
        if (!digest(item.revision()) || !digest(item.sha256()) || item.report() == null || !hash(encoded(item.report())).equals(item.sha256()))
            throw invalid("历史来源修订或内容 SHA-256 不合法");
        var p = item.report();
        if (p.scenarioName() == null || p.scenarioName().isBlank() || p.scenarioName().length() > 160 || p.scenarioVersion() < 1
                || !TERMINAL.contains(p.status() == null ? "" : p.status()) || (p.statusCode() != null && !p.statusCode().matches("[A-Z0-9_]{1,80}"))
                || p.requestedAt() == null || p.deadlineAt() == null || p.deadlineAt().isBefore(p.requestedAt())
                || (p.executionProfileVersion() != null && p.executionProfileVersion() < 1) || (p.dataSetRowNumber() != null && p.dataSetRowNumber() < 1)
                || p.summary() == null || p.parallelGroups() == null || p.parallelGroups().size() > 300
                || p.httpResults() == null || p.httpResults().size() > 300 || p.stepResults() == null || p.stepResults().size() > 300
                || p.scriptResults() == null || p.scriptResults().size() > 300) throw invalid("历史场景、状态、时间或结果数量不合法");
        time(p.requestedAt()); time(p.deadlineAt()); time(p.startedAt()); time(p.finishedAt());
        if ((p.startedAt() != null && p.startedAt().isBefore(p.requestedAt())) || (p.finishedAt() != null && p.finishedAt().isBefore(p.requestedAt()))
                || (p.startedAt() != null && p.finishedAt() != null && p.finishedAt().isBefore(p.startedAt()))) throw invalid("历史时间顺序不合法");
        if (p.manifest() != null && (!digest(p.manifest().checksum()) || !environment(p.manifest().environmentCode())
                || !Set.of("STOP", "CONTINUE").contains(p.manifest().failureStrategy() == null ? "" : p.manifest().failureStrategy()) || p.manifest().stepCount() < 0
                || p.manifest().variableCount() < 0 || p.manifest().secretReferenceCount() < 0)) throw invalid("历史清单摘要不合法");
        if (p.runtime() != null && (!digest(p.runtime().checksum()) || !environment(p.runtime().environmentCode())
                || p.runtime().variableCount() < 0 || p.runtime().withheldSecretCount() < 0)) throw invalid("历史运行摘要不合法");
        if (p.failureStrategy() != null && (!Set.of("STOP", "CONTINUE").contains(p.failureStrategy().strategy() == null ? "" : p.failureStrategy().strategy())
                || p.failureStrategy().continuedFailureCount() < 0 || p.manifest() == null || !p.failureStrategy().strategy().equals(p.manifest().failureStrategy()))) throw invalid("历史失败策略不合法");
        var s = p.summary();
        if (s.httpResultCount() < p.httpResults().size() || s.httpSucceededCount() < 0 || s.httpFailedCount() < 0
                || (long) s.httpSucceededCount() + s.httpFailedCount() != s.httpResultCount() || s.httpDurationMillis() < 0
                || s.stepResultCount() < p.stepResults().size() || s.assertionPassedCount() < 0 || s.assertionFailedCount() < 0
                || (long) s.assertionPassedCount() + s.assertionFailedCount() > s.stepResultCount()
                || s.scriptResultCount() < p.scriptResults().size() || s.scriptTestCount() < 0 || s.scriptPassedCount() < 0 || s.scriptFailedCount() < 0
                || (long) s.scriptPassedCount() + s.scriptFailedCount() != s.scriptTestCount()) throw invalid("历史汇总结果不一致");
        var positions = new HashSet<String>();
        for (var r : p.httpResults()) {
            if (r == null || !step(r.stepId()) || r.requestSequence() < 1 || r.attemptNumber() < 1 || r.attemptNumber() > 3
                    || !positions.add(r.requestSequence() + "." + r.attemptNumber()) || !Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS").contains(r.method() == null ? "" : r.method())
                    || !Set.of("SUCCEEDED", "FAILED").contains(r.outcome() == null ? "" : r.outcome()) || r.durationMillis() < 0 || !httpCode(r.statusCode())) throw invalid("HTTP 历史结果不合法");
            time(r.occurredAt()); if (r.occurredAt() == null) throw invalid("HTTP 历史结果缺少时间");
        }
        for (var r : p.stepResults()) {
            if (r == null || !step(r.stepId()) || r.stepSequence() < 1 || !Set.of("EXTRACT", "ASSERT").contains(r.stepType() == null ? "" : r.stepType())
                    || !("EXTRACT".equals(r.stepType()) ? Set.of("COMPLETED", "FAILED") : Set.of("PASSED", "FAILED")).contains(r.outcome() == null ? "" : r.outcome())
                    || !httpCode(r.observedStatusCode()) || r.occurredAt() == null) throw invalid("步骤历史结果不合法");
            time(r.occurredAt());
        }
        for (var r : p.scriptResults()) {
            if (r == null || !Set.of("PRE_REQUEST", "POST_RESPONSE").contains(r.phase() == null ? "" : r.phase()) || r.testCount() < 0 || r.passedCount() < 0 || r.failedCount() < 0
                    || (long) r.passedCount() + r.failedCount() != r.testCount() || r.occurredAt() == null) throw invalid("脚本历史结果不合法");
            time(r.occurredAt());
        }
        for (var g : p.parallelGroups()) {
            if (g == null || !step(g.stepId()) || g.configuredBranchCount() < 1 || g.configuredBranchCount() > 10 || g.completedBranchCount() < 0
                    || g.completedBranchCount() > g.configuredBranchCount() || g.succeededBranchCount() < 0 || g.failedBranchCount() < 0
                    || (long) g.succeededBranchCount() + g.failedBranchCount() != g.completedBranchCount() || g.attemptCount() < g.completedBranchCount()
                    || g.retryAttemptCount() != g.attemptCount() - g.completedBranchCount() || g.totalDurationMillis() < 0
                    || (g.completedBranchCount() == 0 ? g.firstRequestSequence() != null || g.lastRequestSequence() != null
                    : g.firstRequestSequence() == null || g.lastRequestSequence() == null || g.firstRequestSequence() < 1 || g.lastRequestSequence() < g.firstRequestSequence())) throw invalid("并行历史汇总不合法");
        }
        if (encoded(item).length > 1024 * 1024) throw invalid("单条历史报告超过 1 MiB");
    }
    private boolean environment(String value) { return value != null && value.matches("[a-z0-9][a-z0-9_-]{0,63}"); }
    private boolean step(String value) { return value != null && value.matches("[A-Za-z0-9_-]{1,64}"); }
    private boolean httpCode(Integer value) { return value == null || (value >= 100 && value <= 599); }
    private void time(Instant value) {
        if (value != null && (value.isBefore(Instant.ofEpochSecond(1)) || value.isAfter(Instant.ofEpochSecond(2147483647, 499000000))
                || value.getNano() % 1000000 != 0)) throw invalid("历史时间超出毫秒精度或数据库范围");
    }
    private void requireKey(String key) { if (key == null || !key.matches("[A-Z0-9]{26}\\.[A-Z0-9]{26}")) throw invalid("历史来源标识不合法"); }
    private void requireActor(AuthenticatedPrincipal actor, String expected) { if (!actor.userId().equals(expected)) throw conflict("登录账号已变化，历史同步已停止"); }
    private boolean digest(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private boolean uuid(String value) { try { return value != null && value.equals(UUID.fromString(value).toString()); } catch (IllegalArgumentException e) { return false; } }
    public byte[] encoded(Object value) {
        try { return json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(value); }
        catch (java.io.IOException e) { throw new IllegalStateException("历史报告无法序列化", e); }
    }
    private <T> T decoded(byte[] value, Class<T> type) {
        try {
            T result = json.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(value);
            if (result == null) throw invalid("历史资料不能为空"); return result;
        } catch (java.io.IOException e) { throw invalid("历史 JSON 不合法或包含不支持的字段"); }
    }
    private String text(Object value) { return new String(encoded(value), StandardCharsets.UTF_8); }
    public String hash(byte[] value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
    private ResponseStatusException invalid(String value) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, value); }
    private ResponseStatusException conflict(String value) { return new ResponseStatusException(HttpStatus.CONFLICT, value); }
    private ResponseStatusException missing(String value) { return new ResponseStatusException(HttpStatus.NOT_FOUND, value); }
    private ResponseStatusException tooLarge() { return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "历史同步请求不能超过 4 MiB"); }
    public record ArchivedSummary(String key, String scenarioName, String status, Instant requestedAt, Instant startedAt, Instant finishedAt, Instant updatedAt) { }
    public record Inventory(int version, String scope, String projectId, String actorId, String revision, List<Entry> entries) { }
    public record Entry(String key, String revision, boolean nativeRecord, String scenarioName, String status, Instant requestedAt, Instant finishedAt) { }
    public record Item(String key, String revision, String sha256, Payload report) { }
    public record Payload(String scenarioName, int scenarioVersion, Integer executionProfileVersion, Integer dataSetRowNumber,
            String status, String statusCode, Instant requestedAt, Instant deadlineAt, Instant startedAt, Instant finishedAt,
            ApiAutomationExecutionReport.ManifestSummary manifest, ApiAutomationExecutionReport.RuntimeSummary runtime,
            ApiAutomationExecutionReport.FailureStrategySummary failureStrategy, ApiAutomationExecutionReport.ResultSummary summary,
            List<ApiAutomationExecutionReport.ParallelGroupSummary> parallelGroups, List<HttpResult> httpResults, List<StepResult> stepResults, List<ScriptResult> scriptResults) { }
    public record HttpResult(String stepId, int requestSequence, int attemptNumber, String method, String outcome, Integer statusCode, int durationMillis, Instant occurredAt) { }
    public record StepResult(String stepId, int stepSequence, String stepType, String outcome, int observedStatusCode, Instant occurredAt) { }
    public record ScriptResult(String phase, int testCount, int passedCount, int failedCount, Instant occurredAt) { }
    public record Request(String requestKey, String expectedUserId, String expectedRevision, List<Item> items) { }
    public record Result(String key, String revision, String sha256) { }
    public record Receipt(String requestKey, List<Result> results) { }
}
