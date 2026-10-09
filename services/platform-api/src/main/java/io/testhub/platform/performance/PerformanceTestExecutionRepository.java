package io.testhub.platform.performance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import java.sql.Timestamp;
import io.testhub.platform.shared.persistence.PendingExecutionCancellation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PerformanceTestExecutionRepository {
    private static final String TERMINAL_STATUSES = "'SUCCEEDED','FAILED','CANCELLED','REJECTED','TIMED_OUT'";
    private static final String ACTIVE_STATUSES = "'QUEUED','ACCEPTED','RUNNING','CANCELLING'";
    private final JdbcClient jdbc;
    private final PendingExecutionCancellation cancellation;
    private final ObjectMapper json;

    public PerformanceTestExecutionRepository(JdbcClient jdbc, ObjectMapper json, PendingExecutionCancellation cancellation) {
        this.jdbc = jdbc;
        this.cancellation = cancellation;
        this.json = json;
    }

    public List<PerformanceTestExecution> findByProject(String projectId) {
        return jdbc.sql("SELECT " + executionColumns() + " FROM performance_test_execution WHERE project_id = :projectId "
                        + "ORDER BY created_at DESC, id DESC LIMIT 100")
                .param("projectId", projectId).query((rs, row) -> execution(rs)).list();
    }

    public Optional<PerformanceTestExecution> findById(String projectId, String executionId) {
        return jdbc.sql("SELECT " + executionColumns() + " FROM performance_test_execution WHERE project_id = :projectId AND id = :executionId")
                .param("projectId", projectId).param("executionId", executionId).query((rs, row) -> execution(rs)).optional();
    }

    public Optional<PerformanceTestExecution> findByTask(String taskId, String executionId) {
        return jdbc.sql("SELECT " + executionColumns() + " FROM performance_test_execution WHERE task_id = :taskId AND id = :executionId")
                .param("taskId", taskId).param("executionId", executionId).query((rs, row) -> execution(rs)).optional();
    }

    public Optional<PerformanceTestExecution> findLatestSucceededBefore(String projectId, String planId, String executionId,
                                                                          Instant requestedAt) {
        return jdbc.sql("SELECT " + executionColumns() + " FROM performance_test_execution "
                        + "WHERE project_id = :projectId AND plan_id = :planId AND status = 'SUCCEEDED' "
                        + "AND id <> :executionId AND requested_at <= :requestedAt "
                        + "ORDER BY requested_at DESC, id DESC LIMIT 1")
                .param("projectId", projectId).param("planId", planId).param("executionId", executionId)
                .param("requestedAt", Timestamp.from(requestedAt)).query((rs, row) -> execution(rs)).optional();
    }

    public List<PerformanceTestExecutionLog> findLogs(String projectId, String executionId) {
        return jdbc.sql("""
                SELECT id, execution_id, source, runner_id, level, code, message, occurred_at, created_at
                FROM performance_test_execution_log
                WHERE project_id = :projectId AND execution_id = :executionId
                ORDER BY occurred_at ASC, created_at ASC, id ASC LIMIT 200
                """).param("projectId", projectId).param("executionId", executionId).query((rs, row) ->
                new PerformanceTestExecutionLog(rs.getString("id"), rs.getString("execution_id"), rs.getString("source"),
                        rs.getString("runner_id"), rs.getString("level"), rs.getString("code"), rs.getString("message"),
                        instant(rs.getTimestamp("occurred_at")), instant(rs.getTimestamp("created_at")))).list();
    }

    public Optional<PerformanceTestExecutionManifest> findManifest(String projectId, String executionId) {
        return jdbc.sql("""
                SELECT id, execution_id, plan_id, plan_version, checksum, thread_group_count, content, created_at
                FROM performance_test_execution_manifest
                WHERE project_id = :projectId AND execution_id = :executionId
                """).param("projectId", projectId).param("executionId", executionId).query((rs, row) ->
                new PerformanceTestExecutionManifest(rs.getString("id"), rs.getString("execution_id"),
                        rs.getString("plan_id"), rs.getInt("plan_version"), rs.getString("checksum"),
                        rs.getInt("thread_group_count"), manifestSnapshot(rs.getString("content")),
                        instant(rs.getTimestamp("created_at")))).optional();
    }

    public List<PerformanceTestExecutionMetric> findMetrics(String projectId, String executionId) {
        return jdbc.sql("""
                SELECT id, execution_id, runner_id, thread_group_name, sample_count, success_count, error_count,
                       active_threads, throughput_per_second, average_latency_millis, p95_latency_millis,
                       min_latency_millis, max_latency_millis, occurred_at
                FROM performance_test_execution_metric
                WHERE project_id = :projectId AND execution_id = :executionId
                ORDER BY occurred_at ASC, id ASC LIMIT 2000
                """).param("projectId", projectId).param("executionId", executionId).query((rs, row) ->
                new PerformanceTestExecutionMetric(rs.getString("id"), rs.getString("execution_id"),
                        rs.getString("runner_id"), rs.getString("thread_group_name"), rs.getLong("sample_count"),
                        rs.getLong("success_count"), rs.getLong("error_count"), rs.getInt("active_threads"),
                        rs.getBigDecimal("throughput_per_second"), rs.getInt("average_latency_millis"),
                        rs.getInt("p95_latency_millis"), rs.getInt("min_latency_millis"),
                        rs.getInt("max_latency_millis"), instant(rs.getTimestamp("occurred_at")))).list();
    }

    public List<PerformanceTestExecutionResourceMetric> findResources(String projectId, String executionId) {
        return jdbc.sql("""
                SELECT id, execution_id, runner_id, cpu_percent, resident_memory_bytes, occurred_at
                FROM performance_test_execution_resource_metric
                WHERE project_id = :projectId AND execution_id = :executionId
                ORDER BY occurred_at ASC, id ASC LIMIT 2000
                """).param("projectId", projectId).param("executionId", executionId).query((rs, row) ->
                new PerformanceTestExecutionResourceMetric(rs.getString("id"), rs.getString("execution_id"),
                        rs.getString("runner_id"), rs.getBigDecimal("cpu_percent"), rs.getLong("resident_memory_bytes"),
                        instant(rs.getTimestamp("occurred_at")))).list();
    }

    public List<PerformanceTestExecutionError> findErrors(String projectId, String executionId) {
        return jdbc.sql("""
                SELECT id, execution_id, runner_id, thread_group_name, response_code, error_count, occurred_at
                FROM performance_test_execution_error
                WHERE project_id = :projectId AND execution_id = :executionId
                ORDER BY error_count DESC, occurred_at DESC, id ASC LIMIT 50
                """).param("projectId", projectId).param("executionId", executionId).query((rs, row) ->
                new PerformanceTestExecutionError(rs.getString("id"), rs.getString("execution_id"), rs.getString("runner_id"),
                        rs.getString("thread_group_name"), rs.getString("response_code"), rs.getLong("error_count"),
                        instant(rs.getTimestamp("occurred_at")))).list();
    }

    public void insertExecution(String id, String taskId, String organizationId, String projectId, PerformanceTestPlanDetail plan,
                                String manifestRef, String requestedBy, Instant requestedAt, Instant deadlineAt) {
        jdbc.sql("""
                INSERT INTO performance_test_execution
                    (id, task_id, organization_id, project_id, plan_id, plan_name, plan_version, manifest_ref,
                     requested_by, requested_at, deadline_at)
                VALUES (:id, :taskId, :organizationId, :projectId, :planId, :planName, :planVersion, :manifestRef,
                        :requestedBy, :requestedAt, :deadlineAt)
                """).param("id", id).param("taskId", taskId).param("organizationId", organizationId)
                .param("projectId", projectId).param("planId", plan.id()).param("planName", plan.name())
                .param("planVersion", plan.version()).param("manifestRef", manifestRef).param("requestedBy", requestedBy)
                .param("requestedAt", Timestamp.from(requestedAt)).param("deadlineAt", Timestamp.from(deadlineAt)).update();
    }

    public void insertManifest(String id, PerformanceTestExecution execution, PerformanceTestExecutionManifest.Snapshot snapshot,
                               String content, String checksum) {
        jdbc.sql("""
                INSERT INTO performance_test_execution_manifest
                    (id, execution_id, organization_id, project_id, plan_id, plan_version, content, checksum, thread_group_count)
                VALUES (:id, :executionId, :organizationId, :projectId, :planId, :planVersion, CAST(:content AS JSON),
                        :checksum, :threadGroupCount)
                """).param("id", id).param("executionId", execution.id()).param("organizationId", execution.organizationId())
                .param("projectId", execution.projectId()).param("planId", execution.planId()).param("planVersion", execution.planVersion())
                .param("content", content).param("checksum", checksum).param("threadGroupCount", snapshot.threadGroups().size()).update();
    }

    public void insertOutbox(String id, String executionId, String messageType, String streamKey, Map<String, String> payload) {
        jdbc.sql("""
                INSERT INTO performance_runner_outbox (id, execution_id, message_type, stream_key, payload, status)
                VALUES (:id, :executionId, :messageType, :streamKey, CAST(:payload AS JSON), 'PENDING')
                """).param("id", id).param("executionId", executionId).param("messageType", messageType)
                .param("streamKey", streamKey).param("payload", json(payload)).update();
    }

    public void insertDeferredTaskOutbox(String id, String executionId, Map<String, String> payload, Instant availableAt) {
        jdbc.sql("""
                INSERT INTO performance_runner_outbox (id, execution_id, message_type, stream_key, payload, status, available_at)
                VALUES (:id, :executionId, 'TASK', :streamKey, CAST(:payload AS JSON), 'PENDING', :availableAt)
                """).param("id", id).param("executionId", executionId).param("streamKey", PerformanceRunnerProtocol.TASK_STREAM)
                .param("payload", json(payload)).param("availableAt", Timestamp.from(availableAt)).update();
    }

    public List<PerformanceRunnerOutboxMessage> findDispatchable(int limit) {
        return jdbc.sql("""
                SELECT id, execution_id, message_type, stream_key, payload, dispatch_attempts, max_dispatch_attempts
                FROM performance_runner_outbox
                WHERE status = 'PENDING' AND available_at <= CURRENT_TIMESTAMP(3)
                ORDER BY created_at ASC LIMIT :limit
                """).param("limit", limit).query((rs, row) -> new PerformanceRunnerOutboxMessage(rs.getString("id"),
                rs.getString("execution_id"), rs.getString("message_type"), rs.getString("stream_key"),
                payload(rs.getString("payload")), rs.getInt("dispatch_attempts"), rs.getInt("max_dispatch_attempts"))).list();
    }

    public int claimOutbox(String id) {
        return jdbc.sql("""
                UPDATE performance_runner_outbox SET status = 'DISPATCHING', dispatch_attempts = dispatch_attempts + 1,
                    locked_at = CURRENT_TIMESTAMP(3), updated_at = CURRENT_TIMESTAMP(3)
                WHERE id = :id AND status = 'PENDING' AND available_at <= CURRENT_TIMESTAMP(3)
                    AND dispatch_attempts < max_dispatch_attempts
                """).param("id", id).update();
    }

    public void markOutboxSent(String id) {
        jdbc.sql("""
                UPDATE performance_runner_outbox SET status = 'SENT', sent_at = CURRENT_TIMESTAMP(3), locked_at = NULL,
                    last_error = NULL, updated_at = CURRENT_TIMESTAMP(3)
                WHERE id = :id AND status = 'DISPATCHING'
                """).param("id", id).update();
    }

    public void rescheduleOutbox(String id, String error) {
        jdbc.sql("""
                UPDATE performance_runner_outbox SET
                    status = CASE WHEN dispatch_attempts >= max_dispatch_attempts THEN 'FAILED' ELSE 'PENDING' END,
                    available_at = CASE WHEN dispatch_attempts >= max_dispatch_attempts THEN available_at
                        ELSE DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 5 SECOND) END,
                    locked_at = NULL, last_error = :error, updated_at = CURRENT_TIMESTAMP(3)
                WHERE id = :id AND status = 'DISPATCHING'
                """).param("id", id).param("error", error).update();
    }

    public void releaseStaleOutboxClaims() {
        jdbc.sql("""
                UPDATE performance_runner_outbox
                SET status = CASE WHEN dispatch_attempts >= max_dispatch_attempts THEN 'FAILED' ELSE 'PENDING' END,
                    locked_at = NULL, updated_at = CURRENT_TIMESTAMP(3)
                WHERE status = 'DISPATCHING' AND locked_at < DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE)
                """).update();
    }

    public int cancelBeforeDispatch(String projectId, String executionId) {
        return cancellation.cancel(PendingExecutionCancellation.Engine.PERFORMANCE, projectId, executionId);
    }

    public int markCancelling(String projectId, String executionId) {
        return jdbc.sql("""
                UPDATE performance_test_execution
                SET status = 'CANCELLING', status_code = 'CANCEL_REQUESTED', status_message = '已请求 JMeter Runner 取消任务。',
                    updated_at = CURRENT_TIMESTAMP(3)
                WHERE project_id = :projectId AND id = :executionId AND status IN ('QUEUED', 'ACCEPTED', 'RUNNING')
                """).param("projectId", projectId).param("executionId", executionId).update();
    }

    public void cancelPendingTaskOutbox(String executionId) {
        jdbc.sql("""
                UPDATE performance_runner_outbox
                SET status = 'CANCELLED', locked_at = NULL, last_error = NULL, updated_at = CURRENT_TIMESTAMP(3)
                WHERE execution_id = :executionId AND message_type = 'TASK' AND status = 'PENDING'
                """).param("executionId", executionId).update();
    }

    public boolean hasTaskPossiblyDispatched(String executionId) {
        Integer count = jdbc.sql("""
                SELECT COUNT(*) FROM performance_runner_outbox
                WHERE execution_id = :executionId AND message_type = 'TASK' AND status IN ('DISPATCHING', 'SENT')
                """).param("executionId", executionId).query(Integer.class).single();
        return count != null && count > 0;
    }

    public boolean hasActiveCancellationCommand(String executionId) {
        Integer count = jdbc.sql("""
                SELECT COUNT(*) FROM performance_runner_outbox
                WHERE execution_id = :executionId AND message_type = 'CANCEL' AND status IN ('PENDING', 'DISPATCHING', 'SENT')
                """).param("executionId", executionId).query(Integer.class).single();
        return count != null && count > 0;
    }

    public List<PerformanceTestExecution> findExpiredActive() {
        return jdbc.sql("SELECT " + executionColumns() + " FROM performance_test_execution WHERE status IN (" + ACTIVE_STATUSES
                        + ") AND deadline_at <= CURRENT_TIMESTAMP(3) ORDER BY deadline_at ASC LIMIT 100")
                .query((rs, row) -> execution(rs)).list();
    }

    public List<PerformanceTestExecution> findActiveWithAssignedRunner() {
        return jdbc.sql("SELECT " + executionColumns() + " FROM performance_test_execution "
                        + "WHERE status IN ('ACCEPTED', 'RUNNING', 'CANCELLING') AND runner_id IS NOT NULL "
                        + "ORDER BY updated_at ASC LIMIT 100")
                .query((rs, row) -> execution(rs)).list();
    }

    public int markTimedOut(String executionId) {
        String sql = """
                UPDATE performance_test_execution
                SET status = 'TIMED_OUT', status_code = 'EXECUTION_TIMEOUT',
                    status_message = '任务已达到截止时间，已停止等待 JMeter Runner 结果。',
                    finished_at = CURRENT_TIMESTAMP(3), updated_at = CURRENT_TIMESTAMP(3)
                WHERE id = :executionId AND status IN (%s) AND deadline_at <= CURRENT_TIMESTAMP(3)
                """.formatted(ACTIVE_STATUSES);
        return jdbc.sql(sql)
                .param("executionId", executionId).update();
    }

    public int markRunnerOffline(String executionId, String runnerId) {
        return jdbc.sql("""
                UPDATE performance_test_execution
                SET status = 'FAILED', status_code = 'RUNNER_OFFLINE',
                    status_message = '已分配的 JMeter Runner 心跳已失效；为避免重复压测，任务不会自动在其他 Runner 上重放。',
                    finished_at = CURRENT_TIMESTAMP(3), updated_at = CURRENT_TIMESTAMP(3)
                WHERE id = :executionId AND runner_id = :runnerId AND status IN ('ACCEPTED', 'RUNNING', 'CANCELLING')
                """).param("executionId", executionId).param("runnerId", runnerId).update();
    }

    public int deferForRunnerCapacity(PerformanceRunnerProtocol.PerformanceRunnerEvent event) {
        return jdbc.sql("""
                UPDATE performance_test_execution
                SET routing_attempts = routing_attempts + 1, runner_id = NULL,
                    status = CASE WHEN routing_attempts >= 7 THEN 'FAILED' ELSE 'QUEUED' END,
                    status_code = CASE WHEN routing_attempts >= 7 THEN 'RUNNER_CAPACITY_EXHAUSTED' ELSE 'RUNNER_CAPACITY_DEFERRED' END,
                    status_message = CASE WHEN routing_attempts >= 7
                        THEN 'JMeter Runner 多次没有可用执行槽位，已停止重新投递。'
                        ELSE '接收任务的 JMeter Runner 当前没有空闲执行槽位，任务将重新投递。' END,
                    finished_at = CASE WHEN routing_attempts >= 7 THEN :occurredAt ELSE NULL END,
                    updated_at = CURRENT_TIMESTAMP(3)
                WHERE task_id = :taskId AND id = :executionId AND status = 'QUEUED' AND runner_id IS NULL
                """).param("taskId", event.taskId()).param("executionId", event.executionId())
                .param("occurredAt", Timestamp.from(event.occurredAt())).update();
    }

    public List<PerformanceTestExecution> finalizeExhaustedDeliveries() {
        List<PerformanceTestExecution> failed = new ArrayList<>();
        String candidates = """
                SELECT execution.id
                FROM performance_test_execution execution JOIN performance_runner_outbox outbox ON outbox.execution_id = execution.id
                WHERE execution.status IN (%s) AND outbox.status = 'FAILED'
                GROUP BY execution.id
                ORDER BY MIN(execution.updated_at) ASC LIMIT 100
                """.formatted(ACTIVE_STATUSES);
        List<String> ids = jdbc.sql(candidates).query(String.class).list();
        for (String id : ids) {
            String update = """
                    UPDATE performance_test_execution
                    SET status = 'FAILED', status_code = 'RUNNER_DELIVERY_FAILED',
                        status_message = '任务或取消指令已达到投递上限，已停止重试。',
                        finished_at = CURRENT_TIMESTAMP(3), updated_at = CURRENT_TIMESTAMP(3)
                    WHERE id = :executionId AND status IN (%s)
                    """.formatted(ACTIVE_STATUSES);
            int updated = jdbc.sql(update).param("executionId", id).update();
            if (updated > 0) jdbc.sql("SELECT " + executionColumns() + " FROM performance_test_execution WHERE id = :id")
                    .param("id", id).query((rs, row) -> execution(rs)).optional().ifPresent(failed::add);
        }
        return failed;
    }

    public int applyRunnerEvent(PerformanceRunnerProtocol.PerformanceRunnerEvent event, String status) {
        String sql = """
                UPDATE performance_test_execution
                SET status = :status, status_code = :code, status_message = :message, runner_id = :runnerId,
                    started_at = CASE WHEN :status IN ('ACCEPTED', 'RUNNING') AND started_at IS NULL THEN :occurredAt ELSE started_at END,
                    finished_at = CASE WHEN :status IN (%s) THEN :occurredAt ELSE finished_at END,
                    updated_at = CURRENT_TIMESTAMP(3)
                WHERE task_id = :taskId AND id = :executionId AND (status NOT IN (%s) OR status = :status)
                    AND (runner_id IS NULL OR runner_id = :runnerId)
                """.formatted(TERMINAL_STATUSES, TERMINAL_STATUSES);
        return jdbc.sql(sql)
                .param("taskId", event.taskId()).param("executionId", event.executionId()).param("status", status)
                .param("code", event.code()).param("message", event.message()).param("runnerId", event.runnerId())
                .param("occurredAt", Timestamp.from(event.occurredAt())).update();
    }

    public int claimRuntimeExecution(String taskId, String executionId, String runnerId) {
        return jdbc.sql("""
                UPDATE performance_test_execution
                SET runner_id = :runnerId, status = 'ACCEPTED', status_code = 'RUNTIME_CONFIGURATION_ISSUED',
                    status_message = '已向验证通过的 JMeter Runner 下发受限运行时工件。',
                    started_at = COALESCE(started_at, CURRENT_TIMESTAMP(3)), updated_at = CURRENT_TIMESTAMP(3)
                WHERE task_id = :taskId AND id = :executionId AND status IN ('QUEUED', 'ACCEPTED')
                    AND (runner_id IS NULL OR runner_id = :runnerId)
                """).param("taskId", taskId).param("executionId", executionId).param("runnerId", runnerId).update();
    }

    public void insertMetric(PerformanceTestExecution execution, PerformanceRunnerProtocol.PerformanceRunnerMetric metric) {
        jdbc.sql("""
                INSERT INTO performance_test_execution_metric
                    (id, execution_id, organization_id, project_id, runner_id, thread_group_name, sample_count,
                     success_count, error_count, active_threads, throughput_per_second, average_latency_millis,
                     p95_latency_millis, min_latency_millis, max_latency_millis, occurred_at)
                VALUES (:id, :executionId, :organizationId, :projectId, :runnerId, :threadGroupName, :sampleCount,
                        :successCount, :errorCount, :activeThreads, :throughput, :averageLatency, :p95Latency,
                        :minLatency, :maxLatency, :occurredAt)
                """).param("id", UlidCreator.getUlid().toString()).param("executionId", execution.id())
                .param("organizationId", execution.organizationId()).param("projectId", execution.projectId())
                .param("runnerId", metric.runnerId()).param("threadGroupName", metric.threadGroupName())
                .param("sampleCount", metric.sampleCount()).param("successCount", metric.successCount())
                .param("errorCount", metric.errorCount()).param("activeThreads", metric.activeThreads())
                .param("throughput", metric.throughputPerSecond()).param("averageLatency", metric.averageLatencyMillis())
                .param("p95Latency", metric.p95LatencyMillis()).param("minLatency", metric.minLatencyMillis())
                .param("maxLatency", metric.maxLatencyMillis()).param("occurredAt", Timestamp.from(metric.occurredAt())).update();
    }

    public void insertResource(PerformanceTestExecution execution, PerformanceRunnerProtocol.PerformanceRunnerResource resource) {
        jdbc.sql("""
                INSERT INTO performance_test_execution_resource_metric
                    (id, execution_id, organization_id, project_id, runner_id, cpu_percent, resident_memory_bytes, occurred_at)
                VALUES (:id, :executionId, :organizationId, :projectId, :runnerId, :cpuPercent, :residentMemoryBytes, :occurredAt)
                """).param("id", UlidCreator.getUlid().toString()).param("executionId", execution.id())
                .param("organizationId", execution.organizationId()).param("projectId", execution.projectId())
                .param("runnerId", resource.runnerId()).param("cpuPercent", resource.cpuPercent())
                .param("residentMemoryBytes", resource.residentMemoryBytes()).param("occurredAt", Timestamp.from(resource.occurredAt())).update();
    }

    public void upsertError(PerformanceTestExecution execution, PerformanceRunnerProtocol.PerformanceRunnerError error) {
        jdbc.sql("""
                INSERT INTO performance_test_execution_error
                    (id, execution_id, organization_id, project_id, runner_id, thread_group_name, response_code,
                     error_count, occurred_at)
                VALUES (:id, :executionId, :organizationId, :projectId, :runnerId, :threadGroupName, :responseCode,
                        :errorCount, :occurredAt)
                ON DUPLICATE KEY UPDATE error_count = GREATEST(error_count, VALUES(error_count)),
                    occurred_at = GREATEST(occurred_at, VALUES(occurred_at)), updated_at = CURRENT_TIMESTAMP(3)
                """).param("id", UlidCreator.getUlid().toString()).param("executionId", execution.id())
                .param("organizationId", execution.organizationId()).param("projectId", execution.projectId())
                .param("runnerId", error.runnerId()).param("threadGroupName", error.threadGroupName())
                .param("responseCode", error.responseCode()).param("errorCount", error.errorCount())
                .param("occurredAt", Timestamp.from(error.occurredAt())).update();
    }

    public void appendControlPlaneLog(PerformanceTestExecution execution, String code, String message, Instant occurredAt) {
        appendLog(execution, "CONTROL_PLANE", null, "INFO", code, message, occurredAt);
    }

    public void appendRunnerEventLog(PerformanceTestExecution execution, PerformanceRunnerProtocol.PerformanceRunnerEvent event) {
        appendLog(execution, "RUNNER", event.runnerId(), "INFO", event.code(), event.message(), event.occurredAt());
    }

    private void appendLog(PerformanceTestExecution execution, String source, String runnerId, String level, String code,
                           String message, Instant occurredAt) {
        jdbc.sql("""
                INSERT INTO performance_test_execution_log
                    (id, execution_id, organization_id, project_id, source, runner_id, level, code, message, occurred_at)
                VALUES (:id, :executionId, :organizationId, :projectId, :source, :runnerId, :level, :code, :message, :occurredAt)
                """).param("id", UlidCreator.getMonotonicUlid().toString()).param("executionId", execution.id())
                .param("organizationId", execution.organizationId()).param("projectId", execution.projectId())
                .param("source", source).param("runnerId", runnerId).param("level", level).param("code", code)
                .param("message", message).param("occurredAt", Timestamp.from(occurredAt)).update();
    }

    public String json(Object source) {
        try { return json.writeValueAsString(source); }
        catch (Exception exception) { throw new IllegalStateException("性能任务清单无法序列化", exception); }
    }

    private Map<String, String> payload(String source) {
        try { return Map.copyOf(new LinkedHashMap<>(json.readValue(source, new TypeReference<>() { }))); }
        catch (Exception exception) { throw new IllegalStateException("性能任务 Outbox 内容不合法", exception); }
    }

    private PerformanceTestExecutionManifest.Snapshot manifestSnapshot(String source) {
        try {
            return json.readValue(source, PerformanceTestExecutionManifest.Snapshot.class);
        } catch (Exception exception) {
            throw new IllegalStateException("压测任务清单内容不合法", exception);
        }
    }

    private PerformanceTestExecution execution(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PerformanceTestExecution(rs.getString("id"), rs.getString("task_id"), rs.getString("organization_id"),
                rs.getString("project_id"), rs.getString("plan_id"), rs.getString("plan_name"), rs.getInt("plan_version"),
                rs.getString("status"), rs.getString("status_code"), rs.getString("status_message"), rs.getString("manifest_ref"),
                rs.getString("runner_id"), rs.getInt("routing_attempts"), rs.getString("requested_by"), instant(rs.getTimestamp("requested_at")),
                instant(rs.getTimestamp("deadline_at")), instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("finished_at")),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
    }

    private String executionColumns() {
        return "id, task_id, organization_id, project_id, plan_id, plan_name, plan_version, status, status_code, "
                + "status_message, manifest_ref, runner_id, routing_attempts, requested_by, requested_at, deadline_at, started_at, "
                + "finished_at, created_at, updated_at";
    }

    private Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}
