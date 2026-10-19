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

/** Read-only terminal reports excluding raw request data. Imported rows never enter the Runner lifecycle. */
@Service
public class PerformanceHistorySyncService {
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_ITEMS = 1000;
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "CANCELLED", "REJECTED", "TIMED_OUT");
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private final AccessService access;
    private final PerformanceTestExecutionService executions;
    private final PerformanceTestPlanRepository projects;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    public PerformanceHistorySyncService(AccessService access, PerformanceTestExecutionService executions,
            PerformanceTestPlanRepository projects, AuditService audit, JdbcClient jdbc, ObjectMapper json) {
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
        return new Inventory(1, "PERFORMANCE_HISTORY_SYNC", project, actor.userId(), hash(encoded(entries)), entries);
    }
    /** SQL fingerprints include late result arrivals without materializing all report bodies during every poll. */
    private List<Entry> nativeEntries(String project) { return nativeEntries(project, ""); }
    private List<Entry> nativeEntries(String project, String executionId) {
        return jdbc.sql("""
                SELECT e.id,e.plan_name,e.status,e.requested_at,e.finished_at,e.updated_at,
                  m.checksum AS manifest_checksum,
                  (SELECT COUNT(*) FROM performance_test_execution_metric v WHERE v.execution_id=e.id) AS metric_count,
                  (SELECT MAX(created_at) FROM performance_test_execution_metric v WHERE v.execution_id=e.id) AS metric_latest,
                  (SELECT COUNT(*) FROM performance_test_execution_resource_metric v WHERE v.execution_id=e.id) AS resource_count,
                  (SELECT MAX(created_at) FROM performance_test_execution_resource_metric v WHERE v.execution_id=e.id) AS resource_latest
                FROM performance_test_execution e
                LEFT JOIN performance_test_execution_manifest m ON m.execution_id=e.id
                WHERE e.project_id=:p AND (:execution='' OR e.id=:execution) AND e.status IN ('SUCCEEDED','FAILED','CANCELLED','REJECTED','TIMED_OUT')
                ORDER BY e.id LIMIT 1001
                """).param("p", project).param("execution", executionId).query((rs, n) -> {
                    var fingerprint = new TreeMap<String, String>();
                    for (int column = 1; column <= rs.getMetaData().getColumnCount(); column++)
                        fingerprint.put(rs.getMetaData().getColumnLabel(column), rs.getString(column));
                    String id = rs.getString("id");
                    return new Entry(project + "." + id, hash(encoded(fingerprint)), true, rs.getString("plan_name"),
                            rs.getString("status"), rs.getTimestamp("requested_at").toInstant(),
                            rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant());
                }).list();
    }
    private List<Entry> archivedEntries(String project) {
        return jdbc.sql("""
                SELECT source_project_id,source_execution_id,source_revision,plan_name,status,requested_at,finished_at
                FROM performance_history_archive WHERE project_id=:p
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
            var quality = report.quality();
            var payload = new Payload(e.planName(), e.planVersion(), e.status(), e.statusCode(),
                    e.requestedAt(), e.deadlineAt(), e.startedAt(), e.finishedAt(), report.summary(),
                    new Quality(quality.status(), quality.configuredChecks(), quality.passedChecks(), quality.failedChecks(), quality.inconclusiveChecks(),
                        quality.threadGroups().stream().map(g -> new Threshold(g.threadGroupName(), g.sampleCount(), g.p95LatencyMillis(),
                            g.errorRatePercent(), g.p95ThresholdMillis(), g.errorThresholdPercent(), g.status())).toList()),
                    report.metrics().stream().map(v -> new Metric(v.threadGroupName(), v.sampleCount(), v.successCount(), v.errorCount(),
                        v.activeThreads(), v.throughputPerSecond(), v.averageLatencyMillis(), v.p95LatencyMillis(), v.minLatencyMillis(), v.maxLatencyMillis(), v.occurredAt())).toList(),
                    report.resources().stream().map(v -> new Resource(v.cpuPercent(), v.residentMemoryBytes(), v.occurredAt())).toList());
            var canonical = decoded(encoded(payload), Payload.class);
            var item = new Item(key, entry.revision(), hash(encoded(canonical)), canonical);
            validate(item); return item;
        }
        var stored = jdbc.sql("SELECT report_json,report_sha256 FROM performance_history_archive WHERE project_id=:p AND source_project_id=:sp AND source_execution_id=:se")
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
                SELECT source_project_id,source_execution_id,plan_name,status,requested_at,started_at,finished_at,updated_at
                FROM performance_history_archive WHERE project_id=:p
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
        var prior = jdbc.sql("SELECT payload_sha256,result_json FROM performance_history_sync_receipt WHERE project_id=:p AND requested_by=:u AND request_key=:k")
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
                if (!MessageDigest.isEqual(encoded(read(actor, project, actor.userId(), item.key())), encoded(item))) throw conflict("同一来源修订内容不同，请人工核对");
                continue;
            }
            var p = item.report();
            if (old.isEmpty()) jdbc.sql("""
                    INSERT INTO performance_history_archive
                      (id,organization_id,project_id,source_project_id,source_execution_id,source_revision,report_sha256,report_json,plan_name,status,requested_at,started_at,finished_at,imported_by)
                    VALUES(:id,:o,:p,:sp,:se,:rev,:sha,:report,:name,:status,:requested,:started,:finished,:u)
                    """).param("id", UlidCreator.getUlid().toString()).param("o", context.organizationId()).param("p", project)
                    .param("sp", item.key().substring(0, 26)).param("se", item.key().substring(27)).param("rev", item.revision())
                    .param("sha", item.sha256()).param("report", text(item)).param("name", p.planName()).param("status", p.status())
                    .param("requested", p.requestedAt()).param("started", p.startedAt()).param("finished", p.finishedAt()).param("u", actor.userId()).update();
            else jdbc.sql("""
                    UPDATE performance_history_archive SET source_revision=:rev,report_sha256=:sha,report_json=:report,
                      plan_name=:name,status=:status,requested_at=:requested,started_at=:started,finished_at=:finished,updated_at=CURRENT_TIMESTAMP(3)
                    WHERE project_id=:p AND source_project_id=:sp AND source_execution_id=:se
                    """).param("p", project).param("sp", item.key().substring(0, 26)).param("se", item.key().substring(27))
                    .param("rev", item.revision()).param("sha", item.sha256()).param("report", text(item)).param("name", p.planName())
                    .param("status", p.status()).param("requested", p.requestedAt()).param("started", p.startedAt()).param("finished", p.finishedAt()).update();
        }
        var receipt = new Receipt(request.requestKey(), request.items().stream().map(item -> new Result(item.key(), item.revision(), item.sha256())).toList());
        jdbc.sql("INSERT INTO performance_history_sync_receipt(id,organization_id,project_id,requested_by,request_key,payload_sha256,result_json) VALUES(:id,:o,:p,:u,:k,:h,:r)")
                .param("id", UlidCreator.getUlid().toString()).param("o", context.organizationId()).param("p", project).param("u", actor.userId())
                .param("k", request.requestKey()).param("h", digest).param("r", text(receipt)).update();
        audit.record(actor, context.organizationId(), project, "PERFORMANCE_HISTORY_SYNCED", "PERFORMANCE_HISTORY_SYNC", request.requestKey(), Map.of("reports", request.items().size()));
        return receipt;
    }
    private void validate(Item item) {
        if (item == null) throw invalid("性能历史报告不能为空");
        requireKey(item.key());
        if (!digest(item.revision()) || !digest(item.sha256()) || item.report() == null || !hash(encoded(item.report())).equals(item.sha256()))
            throw invalid("历史来源修订或内容 SHA-256 不合法");
        var p = item.report();
        if (!name(p.planName()) || p.planVersion() < 1 || !TERMINAL.contains(p.status() == null ? "" : p.status())
                || (p.statusCode() != null && !p.statusCode().matches("[A-Z0-9_]{1,80}"))
                || p.requestedAt() == null || p.deadlineAt() == null || p.deadlineAt().isBefore(p.requestedAt())
                || p.summary() == null || p.quality() == null || p.metrics() == null || p.metrics().size() > 2000
                || p.resources() == null || p.resources().size() > 2000) throw invalid("性能历史字段、状态或数量不合法");
        time(p.requestedAt()); time(p.deadlineAt()); time(p.startedAt()); time(p.finishedAt());
        // TIMESTAMP(3) can round the request up while the Runner truncates its event to milliseconds.
        // Preserve both original values and allow that one millisecond, without accepting reordered history.
        if (before(p.startedAt(), p.requestedAt()) || before(p.finishedAt(), p.requestedAt())
                || before(p.finishedAt(), p.startedAt())) throw invalid("历史时间顺序不合法");
        var s = p.summary(); counts(s.sampleCount(), s.successCount(), s.errorCount());
        if (!decimal(s.errorRatePercent(), 100) || !decimal(s.peakThroughputPerSecond(), 1_000_000_000)
                || s.averageLatencyMillis() < 0 || s.p95LatencyMillis() < 0 || s.minLatencyMillis() < 0 || s.maxLatencyMillis() < s.minLatencyMillis()
                || s.peakActiveThreads() < 0) throw invalid("性能历史汇总不合法");
        time(s.firstSampleAt()); time(s.lastSampleAt());
        if ((s.firstSampleAt() == null) != (s.lastSampleAt() == null) || (s.firstSampleAt() != null && s.lastSampleAt().isBefore(s.firstSampleAt()))) throw invalid("采样时间顺序不合法");
        for (var v : p.metrics()) {
            if (v == null || !name(v.threadGroupName()) || v.activeThreads() < 0 || !decimal(v.throughputPerSecond(), 1_000_000_000)
                    || v.averageLatencyMillis() < 0 || v.p95LatencyMillis() < 0 || v.minLatencyMillis() < 0 || v.maxLatencyMillis() < v.minLatencyMillis()
                    || v.occurredAt() == null) throw invalid("性能指标不合法");
            counts(v.sampleCount(), v.successCount(), v.errorCount()); time(v.occurredAt());
        }
        for (var v : p.resources()) {
            if (v == null || !decimal(v.cpuPercent(), 1_000_000) || !safe(v.residentMemoryBytes()) || v.occurredAt() == null) throw invalid("性能资源指标不合法");
            time(v.occurredAt());
        }
        var q = p.quality();
        if (!qualityStatus(q.status()) || q.configuredChecks() < 0 || q.configuredChecks() > 20 || q.passedChecks() < 0 || q.passedChecks() > 20
                || q.failedChecks() < 0 || q.failedChecks() > 20 || q.inconclusiveChecks() < 0 || q.inconclusiveChecks() > 20 || q.threadGroups() == null || q.threadGroups().size() > 10
                || (q.configuredChecks() > 0 && q.passedChecks() + q.failedChecks() + q.inconclusiveChecks() != q.configuredChecks())
                || (q.configuredChecks() == 0 && (q.passedChecks() != 0 || q.failedChecks() != 0 || q.inconclusiveChecks() > 1))) throw invalid("性能质量结论不合法");
        var names = new HashSet<String>();
        for (var v : q.threadGroups()) {
            if (v == null || !name(v.threadGroupName()) || !names.add(v.threadGroupName()) || !safe(v.sampleCount()) || v.p95LatencyMillis() < 0
                    || !decimal(v.errorRatePercent(), 100) || (v.p95ThresholdMillis() != null && (v.p95ThresholdMillis() < 1 || v.p95ThresholdMillis() > 600000))
                    || (v.errorThresholdPercent() != null && !decimal(v.errorThresholdPercent(), 100)) || !qualityStatus(v.status())) throw invalid("性能质量阈值不合法");
        }
        if (encoded(item).length > 1024 * 1024) throw invalid("单条历史报告超过 1 MiB");
    }
    private boolean name(String value) { return value != null && !value.isBlank() && value.length() <= 160; }
    private boolean safe(long value) { return value >= 0 && value <= 9007199254740991L; }
    private void counts(long samples, long success, long errors) {
        if (!safe(samples) || !safe(success) || !safe(errors) || success + errors != samples) throw invalid("性能历史样本计数不一致");
    }
    private boolean decimal(java.math.BigDecimal value, long max) {
        return value != null && value.signum() >= 0 && value.compareTo(java.math.BigDecimal.valueOf(max)) <= 0 && value.stripTrailingZeros().scale() <= 4;
    }
    private boolean qualityStatus(String value) { return value != null && Set.of("PASSED","FAILED","INCONCLUSIVE","NOT_CONFIGURED","NOT_EVALUATED").contains(value); }
    private boolean before(Instant left, Instant right) { return left != null && right != null && left.plusMillis(1).isBefore(right); }
    private void time(Instant value) {
        if (value != null && (value.isBefore(Instant.ofEpochSecond(1)) || value.isAfter(Instant.ofEpochSecond(2147483647, 499000000))
                || value.getNano() % 1000000 != 0)) throw invalid("历史时间超出毫秒精度或数据库范围");
    }
    private void requireKey(String key) { if (key == null || !key.matches("[A-Z0-9]{26}\\.[A-Z0-9]{26}")) throw invalid("历史来源标识不合法"); }
    private void requireActor(AuthenticatedPrincipal actor, String expected) { if (!actor.userId().equals(expected)) throw conflict("登录账号已变化，历史同步已停止"); }
    private boolean digest(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private boolean uuid(String value) { try { return value != null && value.equals(UUID.fromString(value).toString()); } catch (IllegalArgumentException e) { return false; } }
    public byte[] encoded(Object value) {
        try { var tree = json.valueToTree(value);
            normalizeDecimals(tree);
            return json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .with(com.fasterxml.jackson.core.JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN).writeValueAsBytes(tree); }
        catch (java.io.IOException e) { throw new IllegalStateException("历史报告无法序列化", e); }
    }
    // JSON.stringify in the desktop client uses canonical numeric values, not SQL decimal scale.
    private void normalizeDecimals(com.fasterxml.jackson.databind.JsonNode value) {
        if (value instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
            var names = new ArrayList<String>(); object.fieldNames().forEachRemaining(names::add);
            for (String name : names) { var child = object.get(name);
                if (child.isFloatingPointNumber()) object.set(name, com.fasterxml.jackson.databind.node.DecimalNode.valueOf(child.decimalValue().stripTrailingZeros()));
                else normalizeDecimals(child);
            }
        } else if (value.isArray()) for (var child : value) normalizeDecimals(child);
    }
    private <T> T decoded(byte[] value, Class<T> type) {
        try {
            T result = json.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES).readValue(value);
            if (result == null) throw invalid("历史资料不能为空"); return result;
        } catch (java.io.IOException e) { throw invalid("历史 JSON 不合法或包含不支持的字段"); }
    }
    private String text(Object value) { return new String(encoded(value), StandardCharsets.UTF_8); }
    public String hash(byte[] value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
    private ResponseStatusException invalid(String value) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, value); }
    private ResponseStatusException conflict(String value) { return new ResponseStatusException(HttpStatus.CONFLICT, value); }
    private ResponseStatusException missing(String value) { return new ResponseStatusException(HttpStatus.NOT_FOUND, value); }
    private ResponseStatusException tooLarge() { return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "历史同步请求不能超过 4 MiB"); }
    public record ArchivedSummary(String key, String planName, String status, Instant requestedAt, Instant startedAt, Instant finishedAt, Instant updatedAt) { }
    public record Inventory(int version, String scope, String projectId, String actorId, String revision, List<Entry> entries) { }
    public record Entry(String key, String revision, boolean nativeRecord, String planName, String status, Instant requestedAt, Instant finishedAt) { }
    public record Item(String key, String revision, String sha256, Payload report) { }
    public record Payload(String planName, int planVersion, String status, String statusCode,
            Instant requestedAt, Instant deadlineAt, Instant startedAt, Instant finishedAt,
            PerformanceTestExecutionReport.Summary summary, Quality quality, List<Metric> metrics, List<Resource> resources) { }
    public record Metric(String threadGroupName, long sampleCount, long successCount, long errorCount, int activeThreads,
            java.math.BigDecimal throughputPerSecond, int averageLatencyMillis, int p95LatencyMillis, int minLatencyMillis, int maxLatencyMillis, Instant occurredAt) { }
    public record Resource(java.math.BigDecimal cpuPercent, long residentMemoryBytes, Instant occurredAt) { }
    public record Quality(String status, int configuredChecks, int passedChecks, int failedChecks, int inconclusiveChecks, List<Threshold> threadGroups) { }
    public record Threshold(String threadGroupName, long sampleCount, int p95LatencyMillis, java.math.BigDecimal errorRatePercent,
            Integer p95ThresholdMillis, java.math.BigDecimal errorThresholdPercent, String status) { }
    public record Request(String requestKey, String expectedUserId, String expectedRevision, List<Item> items) { }
    public record Result(String key, String revision, String sha256) { }
    public record Receipt(String requestKey, List<Result> results) { }
}
