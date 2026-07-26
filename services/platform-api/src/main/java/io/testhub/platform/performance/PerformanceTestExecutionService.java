package io.testhub.platform.performance;

import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ProjectSummary;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PerformanceTestExecutionService {
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private static final int MAX_ROUTING_ATTEMPTS = 8;
    private static final Map<String, String> EVENT_STATUS = Map.of("ACCEPTED", "ACCEPTED", "RUNNING", "RUNNING",
            "SUCCEEDED", "SUCCEEDED", "FAILED", "FAILED", "CANCELLED", "CANCELLED", "REJECTED", "REJECTED");
    private final PerformanceTestExecutionRepository executions;
    private final PerformanceTestPlanRepository plans;
    private final PerformanceTestExecutionArtifactRepository artifacts;
    private final PerformanceRunnerStatusService runners;
    private final AccessService access;
    private final AuditService audit;

    public PerformanceTestExecutionService(PerformanceTestExecutionRepository executions, PerformanceTestPlanRepository plans,
                                           PerformanceTestExecutionArtifactRepository artifacts, PerformanceRunnerStatusService runners,
                                           AccessService access, AuditService audit) {
        this.executions = executions;
        this.plans = plans;
        this.artifacts = artifacts;
        this.runners = runners;
        this.access = access;
        this.audit = audit;
    }

    public List<PerformanceTestExecution> list(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectRead(principal, projectId);
        return executions.findByProject(projectId);
    }

    public PerformanceTestExecution detail(AuthenticatedPrincipal principal, String projectId, String executionId) {
        access.requireProjectRead(principal, projectId);
        return require(projectId, executionId);
    }

    public List<PerformanceTestExecutionLog> logs(AuthenticatedPrincipal principal, String projectId, String executionId) {
        detail(principal, projectId, executionId);
        return executions.findLogs(projectId, executionId);
    }

    public List<PerformanceTestExecutionMetric> metrics(AuthenticatedPrincipal principal, String projectId, String executionId) {
        detail(principal, projectId, executionId);
        return executions.findMetrics(projectId, executionId);
    }

    public List<PerformanceTestExecutionResourceMetric> resources(AuthenticatedPrincipal principal, String projectId, String executionId) {
        detail(principal, projectId, executionId);
        return executions.findResources(projectId, executionId);
    }

    public PerformanceTestExecutionReport report(AuthenticatedPrincipal principal, String projectId, String executionId) {
        PerformanceTestExecution execution = detail(principal, projectId, executionId);
        List<PerformanceTestExecutionMetric> metrics = executions.findMetrics(projectId, executionId);
        PerformanceTestExecutionReport.Summary summary = summary(metrics);
        PerformanceTestExecutionManifest manifest = executions.findManifest(projectId, executionId).orElse(null);
        PerformanceTestExecutionReport.QualityVerdict quality = quality(execution, manifest, metrics);
        PerformanceTestExecutionReport.Comparison comparison = executions.findLatestSucceededBefore(projectId, execution.planId(),
                        execution.id(), execution.requestedAt()).map(baseline -> {
                    PerformanceTestExecutionReport.Summary baselineSummary = summary(executions.findMetrics(projectId, baseline.id()));
                    return new PerformanceTestExecutionReport.Comparison(baseline, baselineSummary, new PerformanceTestExecutionReport.Delta(
                            summary.sampleCount() - baselineSummary.sampleCount(),
                            summary.errorRatePercent().subtract(baselineSummary.errorRatePercent()),
                            summary.peakThroughputPerSecond().subtract(baselineSummary.peakThroughputPerSecond()),
                            summary.p95LatencyMillis() - baselineSummary.p95LatencyMillis()));
                }).orElse(null);
        return new PerformanceTestExecutionReport(execution, summary, metrics, executions.findResources(projectId, executionId),
                executions.findErrors(projectId, executionId),
                executions.findLogs(projectId, executionId), quality,
                comparison, artifacts.findByExecution(projectId, executionId));
    }

    public CsvExport exportReport(AuthenticatedPrincipal principal, String projectId, String executionId) {
        PerformanceTestExecutionReport report = report(principal, projectId, executionId);
        byte[] content = csv(report).getBytes(StandardCharsets.UTF_8);
        audit.record(principal, report.execution().organizationId(), projectId, "PERFORMANCE_TEST_EXECUTION_REPORT_EXPORTED",
                "PERFORMANCE_TEST_EXECUTION", report.execution().id(), Map.of("executionId", report.execution().id(),
                        "taskId", report.execution().taskId(), "format", "CSV", "sizeBytes", content.length));
        return new CsvExport("testhub-performance-report-" + report.execution().id() + ".csv", content);
    }

    private String csv(PerformanceTestExecutionReport report) {
        StringBuilder result = new StringBuilder("\uFEFF");
        csvRow(result, "段落", "名称", "状态", "样本数", "成功数", "失败数", "错误率(%)", "TPS", "P95(ms)", "平均耗时(ms)",
                "最小耗时(ms)", "最大耗时(ms)", "并发线程", "P95 阈值(ms)", "错误率阈值(%)", "响应状态码", "失败次数", "时间", "说明");
        PerformanceTestExecution execution = report.execution();
        PerformanceTestExecutionReport.Summary summary = report.summary();
        csvRow(result, "摘要", execution.planName(), execution.status(), String.valueOf(summary.sampleCount()),
                String.valueOf(summary.successCount()), String.valueOf(summary.errorCount()), decimal(summary.errorRatePercent()),
                decimal(summary.peakThroughputPerSecond()), String.valueOf(summary.p95LatencyMillis()),
                String.valueOf(summary.averageLatencyMillis()), String.valueOf(summary.minLatencyMillis()),
                String.valueOf(summary.maxLatencyMillis()), String.valueOf(summary.peakActiveThreads()), "", "", "", "",
                instant(summary.lastSampleAt()), "执行 ID=" + execution.id() + "；任务 ID=" + execution.taskId());
        for (PerformanceTestExecutionMetric metric : report.metrics()) {
            csvRow(result, "实时指标", metric.threadGroupName(), "", String.valueOf(metric.sampleCount()),
                    String.valueOf(metric.successCount()), String.valueOf(metric.errorCount()), errorRate(metric).toPlainString(),
                    decimal(metric.throughputPerSecond()), String.valueOf(metric.p95LatencyMillis()),
                    String.valueOf(metric.averageLatencyMillis()), String.valueOf(metric.minLatencyMillis()),
                    String.valueOf(metric.maxLatencyMillis()), String.valueOf(metric.activeThreads()), "", "", "", "",
                    instant(metric.occurredAt()), "");
        }
        for (PerformanceTestExecutionReport.ThreadGroupThreshold threshold : report.quality().threadGroups()) {
            csvRow(result, "质量阈值", threshold.threadGroupName(), threshold.status(), String.valueOf(threshold.sampleCount()), "", "",
                    decimal(threshold.errorRatePercent()), "", String.valueOf(threshold.p95LatencyMillis()), "", "", "", "",
                    nullableNumber(threshold.p95ThresholdMillis()), decimal(threshold.errorThresholdPercent()), "", "", "",
                    String.join("；", threshold.breaches()));
        }
        for (PerformanceTestExecutionError error : report.errors()) {
            csvRow(result, "错误归因", error.threadGroupName(), "", "", "", "", "", "", "", "", "", "", "", "",
                    error.responseCode(), String.valueOf(error.errorCount()), instant(error.occurredAt()), "仅包含失败状态码，不包含响应正文");
        }
        if (report.comparison() != null) {
            PerformanceTestExecutionReport.Comparison comparison = report.comparison();
            csvRow(result, "历史对比", comparison.baseline().planName(), comparison.baseline().status(),
                    String.valueOf(comparison.delta().sampleCount()), "", "", decimal(comparison.delta().errorRatePercent()),
                    decimal(comparison.delta().peakThroughputPerSecond()), String.valueOf(comparison.delta().p95LatencyMillis()),
                    "", "", "", "", "", "", "", "", instant(comparison.baseline().requestedAt()),
                    "差异=当前执行-上一成功执行；基线执行 ID=" + comparison.baseline().id());
        }
        return result.toString();
    }

    private void csvRow(StringBuilder target, String... values) {
        for (int index = 0; index < values.length; index++) {
            if (index > 0) target.append(',');
            target.append(csvCell(values[index]));
        }
        target.append("\r\n");
    }

    private String csvCell(String value) {
        String cell = value == null ? "" : value;
        String leading = cell.stripLeading();
        if (!leading.isEmpty() && "=+-@".indexOf(leading.charAt(0)) >= 0) cell = "'" + cell;
        return '"' + cell.replace("\"", "\"\"") + '"';
    }

    private String decimal(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private String nullableNumber(Integer value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String instant(Instant value) {
        return value == null ? "" : value.toString();
    }

    private PerformanceTestExecutionReport.Summary summary(List<PerformanceTestExecutionMetric> metrics) {
        List<PerformanceTestExecutionMetric> total = metrics.stream()
                .filter(metric -> "__TOTAL__".equals(metric.threadGroupName())).toList();
        PerformanceTestExecutionMetric last = total.isEmpty() ? null : total.getLast();
        BigDecimal errorRate = errorRate(last);
        BigDecimal peakTps = total.stream().map(PerformanceTestExecutionMetric::throughputPerSecond)
                .max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        int peakThreads = total.stream().mapToInt(PerformanceTestExecutionMetric::activeThreads).max().orElse(0);
        return new PerformanceTestExecutionReport.Summary(last == null ? 0 : last.sampleCount(), last == null ? 0 : last.successCount(),
                last == null ? 0 : last.errorCount(), errorRate, peakTps, last == null ? 0 : last.averageLatencyMillis(),
                last == null ? 0 : last.p95LatencyMillis(), last == null ? 0 : last.minLatencyMillis(),
                last == null ? 0 : last.maxLatencyMillis(), peakThreads, total.isEmpty() ? null : total.getFirst().occurredAt(),
                total.isEmpty() ? null : total.getLast().occurredAt());
    }

    private PerformanceTestExecutionReport.QualityVerdict quality(PerformanceTestExecution execution,
                                                                   PerformanceTestExecutionManifest manifest,
                                                                   List<PerformanceTestExecutionMetric> metrics) {
        if (!"SUCCEEDED".equals(execution.status())) {
            return new PerformanceTestExecutionReport.QualityVerdict("NOT_EVALUATED", 0, 0, 0, 0, List.of());
        }
        if (manifest == null) return new PerformanceTestExecutionReport.QualityVerdict("INCONCLUSIVE", 0, 0, 0, 1, List.of());
        int configured = 0;
        int passed = 0;
        int failed = 0;
        int inconclusive = 0;
        List<PerformanceTestExecutionReport.ThreadGroupThreshold> groups = new java.util.ArrayList<>();
        for (PerformanceTestExecutionManifest.ThreadGroup group : manifest.snapshot().threadGroups()) {
            boolean p95Configured = group.p95ThresholdMillis() != null;
            boolean errorConfigured = group.errorThresholdPercent() != null;
            int groupChecks = (p95Configured ? 1 : 0) + (errorConfigured ? 1 : 0);
            configured += groupChecks;
            PerformanceTestExecutionMetric last = metrics.stream().filter(metric -> group.name().equals(metric.threadGroupName()))
                    .reduce((first, second) -> second).orElse(null);
            if (groupChecks == 0) {
                groups.add(new PerformanceTestExecutionReport.ThreadGroupThreshold(group.name(), last == null ? 0 : last.sampleCount(),
                        last == null ? 0 : last.p95LatencyMillis(), errorRate(last), null, null, "NOT_CONFIGURED", List.of()));
                continue;
            }
            if (last == null || last.sampleCount() == 0) {
                inconclusive += groupChecks;
                groups.add(new PerformanceTestExecutionReport.ThreadGroupThreshold(group.name(), 0, 0, BigDecimal.ZERO,
                        group.p95ThresholdMillis(), group.errorThresholdPercent(), "INCONCLUSIVE", List.of("未收到有效线程组指标")));
                continue;
            }
            BigDecimal errorRate = errorRate(last);
            List<String> breaches = new java.util.ArrayList<>();
            if (p95Configured && last.p95LatencyMillis() > group.p95ThresholdMillis()) {
                breaches.add("P95 " + last.p95LatencyMillis() + "ms 超过 " + group.p95ThresholdMillis() + "ms");
            }
            if (errorConfigured && errorRate.compareTo(group.errorThresholdPercent()) > 0) {
                breaches.add("错误率 " + errorRate + "% 超过 " + group.errorThresholdPercent() + "%");
            }
            int breachedChecks = (p95Configured && last.p95LatencyMillis() > group.p95ThresholdMillis() ? 1 : 0)
                    + (errorConfigured && errorRate.compareTo(group.errorThresholdPercent()) > 0 ? 1 : 0);
            passed += groupChecks - breachedChecks;
            failed += breachedChecks;
            groups.add(new PerformanceTestExecutionReport.ThreadGroupThreshold(group.name(), last.sampleCount(), last.p95LatencyMillis(),
                    errorRate, group.p95ThresholdMillis(), group.errorThresholdPercent(), breaches.isEmpty() ? "PASSED" : "FAILED",
                    List.copyOf(breaches)));
        }
        String status = configured == 0 ? "NOT_CONFIGURED" : failed > 0 ? "FAILED" : inconclusive > 0 ? "INCONCLUSIVE" : "PASSED";
        return new PerformanceTestExecutionReport.QualityVerdict(status, configured, passed, failed, inconclusive, List.copyOf(groups));
    }

    private BigDecimal errorRate(PerformanceTestExecutionMetric metric) {
        return metric == null || metric.sampleCount() == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(metric.errorCount()).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(metric.sampleCount()), 2, RoundingMode.HALF_UP);
    }

    @Transactional
    public PerformanceTestExecution create(AuthenticatedPrincipal principal, String projectId, String planId,
                                           Integer timeoutSeconds) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
        PerformanceTestPlanDetail plan = plans.findDetail(projectId, planId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "压测计划不存在"));
        if (!"READY".equals(plan.status())) throw new ResponseStatusException(HttpStatus.CONFLICT, "只有待执行状态的压测计划可以提交任务");
        if (plan.threadGroups().isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "压测计划缺少线程组");
        int timeout = timeout(timeoutSeconds);
        Instant requestedAt = Instant.now();
        String executionId = UlidCreator.getUlid().toString();
        String taskId = UlidCreator.getUlid().toString();
        Instant deadlineAt = requestedAt.plus(Duration.ofSeconds(timeout));
        String manifestRef = "performance-test/manifests/" + executionId + "/v1.json";
        executions.insertExecution(executionId, taskId, project.organizationId(), projectId, plan, manifestRef,
                principal.userId(), requestedAt, deadlineAt);
        PerformanceTestExecution queued = require(projectId, executionId);
        PerformanceTestExecutionManifest.Snapshot manifest = manifestSnapshot(plan);
        String content = executions.json(manifest);
        String checksum = sha256(content);
        executions.insertManifest(UlidCreator.getUlid().toString(), queued, manifest, content, checksum);
        executions.appendControlPlaneLog(queued, "MANIFEST_FROZEN", "已固定压测计划与线程组配置；Runner 队列不包含目标地址或执行参数。", requestedAt);
        executions.insertOutbox(taskId, executionId, "TASK", PerformanceRunnerProtocol.TASK_STREAM,
                taskPayload(taskId, executionId, project.organizationId(), projectId, plan.id(), plan.version(), requestedAt, deadlineAt, manifestRef));
        executions.appendControlPlaneLog(queued, "QUEUED", "任务已写入安全 Outbox，等待 JMeter Runner 投递。", requestedAt);
        audit.record(principal, project.organizationId(), projectId, "PERFORMANCE_TEST_EXECUTION_MANIFEST_FROZEN",
                "PERFORMANCE_TEST_EXECUTION_MANIFEST", executionId, Map.of("planId", plan.id(), "planVersion", plan.version(),
                        "threadGroupCount", plan.threadGroups().size(), "checksum", checksum));
        audit.record(principal, project.organizationId(), projectId, "PERFORMANCE_TEST_EXECUTION_QUEUED",
                "PERFORMANCE_TEST_EXECUTION", executionId, Map.of("taskId", taskId, "planId", plan.id(),
                        "planVersion", plan.version(), "timeoutSeconds", timeout));
        return queued;
    }

    @Transactional
    public PerformanceTestExecution cancel(AuthenticatedPrincipal principal, String projectId, String executionId) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
        PerformanceTestExecution current = detail(principal, projectId, executionId);
        if (terminal(current.status()) || "CANCELLING".equals(current.status())) return current;
        if (executions.cancelBeforeDispatch(projectId, executionId) > 0) {
            PerformanceTestExecution cancelled = require(projectId, executionId);
            executions.appendControlPlaneLog(cancelled, "CANCELLED_BEFORE_DISPATCH", "任务在投递给 JMeter Runner 前已取消。", Instant.now());
            audit.record(principal, project.organizationId(), projectId, "PERFORMANCE_TEST_EXECUTION_CANCELLED",
                    "PERFORMANCE_TEST_EXECUTION", executionId, Map.of("taskId", cancelled.taskId(), "mode", "BEFORE_DISPATCH"));
            return cancelled;
        }
        if (executions.markCancelling(projectId, executionId) == 0) return require(projectId, executionId);
        String commandId = UlidCreator.getUlid().toString();
        Instant requestedAt = Instant.now();
        executions.insertOutbox(commandId, executionId, "CANCEL", PerformanceRunnerProtocol.CANCEL_STREAM,
                cancelPayload(commandId, current, requestedAt, "USER_REQUESTED"));
        PerformanceTestExecution cancelling = require(projectId, executionId);
        executions.appendControlPlaneLog(cancelling, "CANCEL_REQUESTED", "已通过安全 Outbox 请求 JMeter Runner 取消任务。", requestedAt);
        audit.record(principal, project.organizationId(), projectId, "PERFORMANCE_TEST_EXECUTION_CANCEL_REQUESTED",
                "PERFORMANCE_TEST_EXECUTION", executionId, Map.of("taskId", current.taskId(), "commandId", commandId));
        return cancelling;
    }

    @Transactional
    public void expireDueExecutions() {
        for (PerformanceTestExecution candidate : executions.findExpiredActive()) {
            if (executions.markTimedOut(candidate.id()) == 0) continue;
            executions.cancelPendingTaskOutbox(candidate.id());
            if (executions.hasTaskPossiblyDispatched(candidate.id()) && !executions.hasActiveCancellationCommand(candidate.id())) {
                String commandId = UlidCreator.getUlid().toString();
                executions.insertOutbox(commandId, candidate.id(), "CANCEL", PerformanceRunnerProtocol.CANCEL_STREAM,
                        cancelPayload(commandId, candidate, Instant.now(), "EXECUTION_TIMEOUT"));
            }
            executions.appendControlPlaneLog(candidate, "EXECUTION_TIMEOUT", "任务已达到截止时间，已停止等待 JMeter Runner 结果。", Instant.now());
            audit.recordSystem(candidate.organizationId(), candidate.projectId(), "PERFORMANCE_TEST_EXECUTION_TIMED_OUT",
                    "PERFORMANCE_TEST_EXECUTION", candidate.id(), Map.of("taskId", candidate.taskId()));
        }
    }

    @Transactional
    public void applyRunnerEvent(PerformanceRunnerProtocol.PerformanceRunnerEvent event) {
        if ("DEFERRED".equals(event.type())) {
            deferForRunnerCapacity(event);
            return;
        }
        String status = EVENT_STATUS.get(event.type());
        if (status == null) throw new IllegalArgumentException("JMeter Runner 事件类型不受支持");
        PerformanceTestExecution current = executions.findByTask(event.taskId(), event.executionId()).orElse(null);
        if (current == null) return;
        if (executions.applyRunnerEvent(event, status) > 0) {
            executions.appendRunnerEventLog(current, event);
            audit.recordSystem(current.organizationId(), current.projectId(), "PERFORMANCE_TEST_EXECUTION_EVENT_RECEIVED",
                    "PERFORMANCE_TEST_EXECUTION", current.id(), Map.of("taskId", current.taskId(), "runnerId", event.runnerId(),
                            "type", event.type(), "code", event.code()));
        }
    }

    @Transactional
    public void expireOfflineRunnerExecutions() {
        for (PerformanceTestExecution candidate : executions.findActiveWithAssignedRunner()) {
            if (runners.isLive(candidate.runnerId()) || executions.markRunnerOffline(candidate.id(), candidate.runnerId()) == 0) continue;
            PerformanceTestExecution failed = require(candidate.projectId(), candidate.id());
            executions.cancelPendingTaskOutbox(failed.id());
            executions.appendControlPlaneLog(failed, "RUNNER_OFFLINE",
                    "Runner 心跳在任务结束前失效。为了避免重复压测，控制面已将本次执行明确标记为失败。", Instant.now());
            audit.recordSystem(failed.organizationId(), failed.projectId(), "PERFORMANCE_TEST_EXECUTION_RUNNER_OFFLINE",
                    "PERFORMANCE_TEST_EXECUTION", failed.id(), Map.of("taskId", failed.taskId(), "runnerId", candidate.runnerId()));
        }
    }

    private void deferForRunnerCapacity(PerformanceRunnerProtocol.PerformanceRunnerEvent event) {
        if (!"RUNNER_CAPACITY_EXHAUSTED".equals(event.code())) {
            throw new IllegalArgumentException("JMeter Runner 延迟事件代码不受支持");
        }
        PerformanceTestExecution current = executions.findByTask(event.taskId(), event.executionId()).orElse(null);
        if (current == null || executions.deferForRunnerCapacity(event) == 0) return;
        PerformanceTestExecution deferred = require(current.projectId(), current.id());
        executions.appendRunnerEventLog(current, event);
        if ("FAILED".equals(deferred.status())) {
            audit.recordSystem(deferred.organizationId(), deferred.projectId(), "PERFORMANCE_TEST_EXECUTION_CAPACITY_EXHAUSTED",
                    "PERFORMANCE_TEST_EXECUTION", deferred.id(), Map.of("taskId", deferred.taskId(),
                            "routingAttempts", deferred.routingAttempts(), "maxRoutingAttempts", MAX_ROUTING_ATTEMPTS));
            return;
        }
        Instant nextDispatchAt = Instant.now().plusSeconds(Math.min(30, Math.max(2, deferred.routingAttempts() * 2L)));
        executions.insertDeferredTaskOutbox(UlidCreator.getUlid().toString(), deferred.id(), taskPayload(deferred), nextDispatchAt);
        executions.appendControlPlaneLog(deferred, "RUNNER_REDISPATCH_SCHEDULED",
                "任务将等待其他有空闲槽位的 JMeter Runner；不会在当前 Runner 上并发启动。", Instant.now());
        audit.recordSystem(deferred.organizationId(), deferred.projectId(), "PERFORMANCE_TEST_EXECUTION_CAPACITY_DEFERRED",
                "PERFORMANCE_TEST_EXECUTION", deferred.id(), Map.of("taskId", deferred.taskId(), "runnerId", event.runnerId(),
                        "routingAttempts", deferred.routingAttempts(), "nextDispatchAt", nextDispatchAt.toString()));
    }

    @Transactional
    public void applyRunnerMetric(PerformanceRunnerProtocol.PerformanceRunnerMetric metric) {
        PerformanceTestExecution execution = executions.findByTask(metric.taskId(), metric.executionId())
                .orElseThrow(() -> new IllegalArgumentException("JMeter Runner 指标关联的任务不存在"));
        if (!Set.of("QUEUED", "ACCEPTED", "RUNNING", "CANCELLING").contains(execution.status())
                || (execution.runnerId() != null && !execution.runnerId().equals(metric.runnerId()))) {
            throw new IllegalArgumentException("JMeter Runner 指标不属于活动任务");
        }
        executions.insertMetric(execution, metric);
    }

    @Transactional
    public void applyRunnerResource(PerformanceRunnerProtocol.PerformanceRunnerResource resource) {
        PerformanceTestExecution execution = executions.findByTask(resource.taskId(), resource.executionId())
                .orElseThrow(() -> new IllegalArgumentException("JMeter Runner 资源指标关联的任务不存在"));
        if (!Set.of("QUEUED", "ACCEPTED", "RUNNING", "CANCELLING").contains(execution.status())
                || (execution.runnerId() != null && !execution.runnerId().equals(resource.runnerId()))) {
            throw new IllegalArgumentException("JMeter Runner 资源指标不属于活动任务");
        }
        executions.insertResource(execution, resource);
    }

    @Transactional
    public void applyRunnerError(PerformanceRunnerProtocol.PerformanceRunnerError error) {
        PerformanceTestExecution execution = executions.findByTask(error.taskId(), error.executionId())
                .orElseThrow(() -> new IllegalArgumentException("JMeter Runner 错误归因关联的任务不存在"));
        if (execution.runnerId() == null || !execution.runnerId().equals(error.runnerId())) {
            throw new IllegalArgumentException("JMeter Runner 错误归因不属于该执行任务");
        }
        executions.upsertError(execution, error);
    }

    private PerformanceTestExecutionManifest.Snapshot manifestSnapshot(PerformanceTestPlanDetail plan) {
        return new PerformanceTestExecutionManifest.Snapshot("testhub.performance.execution-manifest", 4, plan.name(),
                plan.threadGroups().stream().map(group -> new PerformanceTestExecutionManifest.ThreadGroup(group.name(),
                        group.targetUrl(), group.loadModel(), group.threadCount(), group.rampUpSeconds(), group.stepCount(), group.stepIntervalSeconds(), group.durationSeconds(),
                        group.targetTps(), group.p95ThresholdMillis(), group.errorThresholdPercent(), group.requestMethod(),
                        group.requestHeaders(), group.queryParameters(), group.requestBody(), group.expectedStatusCode(), group.responseContains(),
                        group.dataSetCsv(), group.rendezvousUsers())).toList());
    }

    private Map<String, String> taskPayload(String taskId, String executionId, String organizationId, String projectId,
                                            String planId, int planVersion, Instant requestedAt, Instant deadlineAt,
                                            String manifestRef) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("contract", PerformanceRunnerProtocol.TASK_CONTRACT);
        payload.put("protocolVersion", PerformanceRunnerProtocol.VERSION);
        payload.put("taskId", taskId);
        payload.put("executionId", executionId);
        payload.put("organizationId", organizationId);
        payload.put("projectId", projectId);
        payload.put("planId", planId);
        payload.put("planVersion", String.valueOf(planVersion));
        payload.put("requestedAt", requestedAt.toString());
        payload.put("deadlineAt", deadlineAt.toString());
        payload.put("manifestRef", manifestRef);
        payload.put("traceId", "performance-" + executionId);
        return Map.copyOf(payload);
    }

    private Map<String, String> taskPayload(PerformanceTestExecution execution) {
        return taskPayload(execution.taskId(), execution.id(), execution.organizationId(), execution.projectId(), execution.planId(),
                execution.planVersion(), execution.requestedAt(), execution.deadlineAt(), execution.manifestRef());
    }

    private Map<String, String> cancelPayload(String commandId, PerformanceTestExecution execution, Instant requestedAt,
                                              String reasonCode) {
        return Map.of("contract", PerformanceRunnerProtocol.CANCEL_CONTRACT, "protocolVersion", PerformanceRunnerProtocol.VERSION,
                "commandId", commandId, "taskId", execution.taskId(), "executionId", execution.id(),
                "requestedAt", requestedAt.toString(), "reasonCode", reasonCode);
    }

    private int timeout(Integer value) {
        int timeout = value == null ? 3_600 : value;
        if (timeout < 60 || timeout > 86_400) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "压测超时时间应在 60 到 86400 秒之间");
        return timeout;
    }

    private boolean terminal(String status) {
        return Set.of("SUCCEEDED", "FAILED", "CANCELLED", "REJECTED", "TIMED_OUT").contains(status);
    }

    private PerformanceTestExecution require(String projectId, String executionId) {
        return executions.findById(projectId, executionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "压测执行任务不存在"));
    }

    private String sha256(String source) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException("压测任务清单校验失败", exception); }
    }

    public record CsvExport(String filename, byte[] content) {
    }
}
