package io.testhub.platform.apiautomation;

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
public class ApiAutomationExecutionRepository {
    private static final String TERMINAL_STATUSES = "'SUCCEEDED','FAILED','CANCELLED','REJECTED','TIMED_OUT'";
    private static final String ACTIVE_STATUSES = "'QUEUED','ACCEPTED','RUNNING','CANCELLING'";
    private final JdbcClient jdbc;
    private final PendingExecutionCancellation cancellation;
    private final ObjectMapper json;

    public ApiAutomationExecutionRepository(JdbcClient jdbc, ObjectMapper json, PendingExecutionCancellation cancellation) {
        this.jdbc = jdbc;
        this.cancellation = cancellation;
        this.json = json;
    }

    public List<ApiAutomationExecution> findByProject(String projectId) {
        return jdbc.sql("""
                SELECT id, task_id, organization_id, project_id, scenario_id, scenario_name, scenario_version, execution_profile_id,
                       execution_profile_version, data_set_id,
                       data_set_row_number, status, status_code, status_message, manifest_ref, runner_id, requested_by, requested_at, deadline_at,
                       started_at, finished_at, created_at, updated_at
                FROM api_automation_execution WHERE project_id = :projectId
                ORDER BY created_at DESC, id DESC LIMIT 100
                """).param("projectId", projectId).query((rs, row) -> execution(rs)).list();
    }

    public Optional<ApiAutomationExecution> findById(String projectId, String executionId) {
        return jdbc.sql("""
                SELECT id, task_id, organization_id, project_id, scenario_id, scenario_name, scenario_version, execution_profile_id,
                       execution_profile_version, data_set_id,
                       data_set_row_number, status, status_code, status_message, manifest_ref, runner_id, requested_by, requested_at, deadline_at,
                       started_at, finished_at, created_at, updated_at
                FROM api_automation_execution WHERE project_id = :projectId AND id = :executionId
                """).param("projectId", projectId).param("executionId", executionId)
                .query((rs, row) -> execution(rs)).optional();
    }

    public Optional<ApiAutomationExecution> findByTask(String taskId, String executionId) {
        return jdbc.sql("""
                SELECT id, task_id, organization_id, project_id, scenario_id, scenario_name, scenario_version, execution_profile_id,
                       execution_profile_version, data_set_id,
                       data_set_row_number, status, status_code, status_message, manifest_ref, runner_id, requested_by, requested_at, deadline_at,
                       started_at, finished_at, created_at, updated_at
                FROM api_automation_execution WHERE task_id = :taskId AND id = :executionId
                """).param("taskId", taskId).param("executionId", executionId)
                .query((rs, row) -> execution(rs)).optional();
    }

    public List<ApiAutomationExecutionLog> findLogs(String projectId, String executionId) {
        return jdbc.sql("""
                SELECT id, execution_id, source, runner_id, level, code, message, occurred_at, created_at
                FROM api_automation_execution_log
                WHERE project_id = :projectId AND execution_id = :executionId
                ORDER BY occurred_at ASC, created_at ASC, id ASC LIMIT 200
                """).param("projectId", projectId).param("executionId", executionId)
                .query((rs, row) -> executionLog(rs)).list();
    }

    /** Counts a whitelisted fixed Runner log without exporting its message or any user-authored content. */
    public int countRunnerLogsByCode(String projectId, String executionId, String code) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM api_automation_execution_log
                WHERE project_id = :projectId AND execution_id = :executionId AND source = 'RUNNER' AND code = :code
                """).param("projectId", projectId).param("executionId", executionId).param("code", code)
                .query(Integer.class).single();
    }

    public List<ApiAutomationExecutionHttpResult> findHttpResults(String projectId, String executionId) {
        return jdbc.sql("""
                SELECT id, execution_id, step_id, request_sequence, attempt_number, method, outcome, status_code, duration_millis,
                       runner_id, occurred_at, created_at
                FROM api_automation_execution_http_result
                WHERE project_id = :projectId AND execution_id = :executionId
                ORDER BY request_sequence ASC, attempt_number ASC, occurred_at ASC, id ASC LIMIT 300
                """).param("projectId", projectId).param("executionId", executionId)
                .query((rs, row) -> new ApiAutomationExecutionHttpResult(rs.getString("id"), rs.getString("execution_id"),
                        rs.getString("step_id"), rs.getInt("request_sequence"), rs.getInt("attempt_number"), rs.getString("method"),
                        rs.getString("outcome"), rs.getObject("status_code", Integer.class), rs.getInt("duration_millis"),
                        rs.getString("runner_id"), instant(rs.getTimestamp("occurred_at")),
                        instant(rs.getTimestamp("created_at")))).list();
    }

    public List<ApiAutomationExecutionStepResult> findStepResults(String projectId, String executionId) {
        return jdbc.sql("""
                SELECT id, execution_id, step_id, step_sequence, step_type, outcome, observed_status_code, runner_id,
                       occurred_at, created_at
                FROM api_automation_execution_step_result
                WHERE project_id = :projectId AND execution_id = :executionId
                ORDER BY step_sequence ASC, occurred_at ASC, id ASC LIMIT 300
                """).param("projectId", projectId).param("executionId", executionId)
                .query((rs, row) -> new ApiAutomationExecutionStepResult(rs.getString("id"), rs.getString("execution_id"),
                        rs.getString("step_id"), rs.getInt("step_sequence"), rs.getString("step_type"),
                        rs.getString("outcome"), rs.getInt("observed_status_code"), rs.getString("runner_id"),
                        instant(rs.getTimestamp("occurred_at")), instant(rs.getTimestamp("created_at")))).list();
    }

    public List<ApiAutomationExecutionScriptResult> findScriptResults(String projectId, String executionId) {
        return jdbc.sql("""
                SELECT id, execution_id, script_phase, test_count, passed_count, failed_count, runner_id, occurred_at, created_at
                FROM api_automation_execution_script_result
                WHERE project_id = :projectId AND execution_id = :executionId
                ORDER BY occurred_at ASC, created_at ASC, id ASC LIMIT 300
                """).param("projectId", projectId).param("executionId", executionId)
                .query((rs, row) -> new ApiAutomationExecutionScriptResult(rs.getString("id"), rs.getString("execution_id"),
                        rs.getString("script_phase"), rs.getInt("test_count"), rs.getInt("passed_count"),
                        rs.getInt("failed_count"), rs.getString("runner_id"), instant(rs.getTimestamp("occurred_at")),
                        instant(rs.getTimestamp("created_at")))).list();
    }

    public ExecutionResultTotals resultTotals(String projectId, String executionId) {
        ExecutionResultTotals http = jdbc.sql("""
                SELECT COUNT(*) AS result_count,
                       COALESCE(SUM(outcome = 'SUCCEEDED'), 0) AS succeeded_count,
                       COALESCE(SUM(outcome = 'FAILED'), 0) AS failed_count,
                       COALESCE(SUM(duration_millis), 0) AS duration_millis
                FROM api_automation_execution_http_result
                WHERE project_id = :projectId AND execution_id = :executionId
                """).param("projectId", projectId).param("executionId", executionId)
                .query((rs, row) -> new ExecutionResultTotals(rs.getInt("result_count"), rs.getInt("succeeded_count"),
                        rs.getInt("failed_count"), rs.getLong("duration_millis"), 0, 0, 0, 0, 0, 0, 0)).single();
        ExecutionResultTotals steps = jdbc.sql("""
                SELECT COUNT(*) AS result_count,
                       COALESCE(SUM(step_type = 'ASSERT' AND outcome = 'PASSED'), 0) AS assertion_passed_count,
                       COALESCE(SUM(step_type = 'ASSERT' AND outcome = 'FAILED'), 0) AS assertion_failed_count
                FROM api_automation_execution_step_result
                WHERE project_id = :projectId AND execution_id = :executionId
                """).param("projectId", projectId).param("executionId", executionId)
                .query((rs, row) -> new ExecutionResultTotals(http.httpResultCount(), http.httpSucceededCount(),
                        http.httpFailedCount(), http.httpDurationMillis(), rs.getInt("result_count"),
                        rs.getInt("assertion_passed_count"), rs.getInt("assertion_failed_count"), 0, 0, 0, 0)).single();
        return jdbc.sql("""
                SELECT COUNT(*) AS result_count, COALESCE(SUM(test_count), 0) AS test_count,
                       COALESCE(SUM(passed_count), 0) AS passed_count, COALESCE(SUM(failed_count), 0) AS failed_count
                FROM api_automation_execution_script_result
                WHERE project_id = :projectId AND execution_id = :executionId
                """).param("projectId", projectId).param("executionId", executionId)
                .query((rs, row) -> new ExecutionResultTotals(steps.httpResultCount(), steps.httpSucceededCount(),
                        steps.httpFailedCount(), steps.httpDurationMillis(), steps.stepResultCount(),
                        steps.assertionPassedCount(), steps.assertionFailedCount(), rs.getInt("result_count"),
                        rs.getInt("test_count"), rs.getInt("passed_count"), rs.getInt("failed_count"))).single();
    }

    public Optional<ApiAutomationExecutionManifest> findManifest(String projectId, String executionId) {
        return jdbc.sql("""
                SELECT id, execution_id, scenario_id, scenario_version, content, checksum, step_count, variable_count,
                       secret_reference_count, created_at
                FROM api_automation_execution_manifest
                WHERE project_id = :projectId AND execution_id = :executionId
                """).param("projectId", projectId).param("executionId", executionId)
                .query((rs, row) -> new ApiAutomationExecutionManifest(rs.getString("id"), rs.getString("execution_id"),
                        rs.getString("scenario_id"), rs.getInt("scenario_version"), rs.getString("checksum"),
                        rs.getInt("step_count"), rs.getInt("variable_count"), rs.getInt("secret_reference_count"),
                        manifestSnapshot(rs.getString("content")), instant(rs.getTimestamp("created_at")))).optional();
    }

    public Optional<ApiAutomationExecutionRuntimeSnapshot> findRuntimeSnapshot(String projectId, String executionId) {
        return jdbc.sql("""
                SELECT id, execution_id, content, checksum, variable_count, withheld_secret_count, created_at
                FROM api_automation_execution_runtime_snapshot
                WHERE project_id = :projectId AND execution_id = :executionId
                """).param("projectId", projectId).param("executionId", executionId)
                .query((rs, row) -> new ApiAutomationExecutionRuntimeSnapshot(rs.getString("id"),
                        rs.getString("execution_id"), rs.getString("checksum"), rs.getInt("variable_count"),
                        rs.getInt("withheld_secret_count"), runtimeSnapshot(rs.getString("content")),
                        instant(rs.getTimestamp("created_at")))).optional();
    }

    public void appendControlPlaneLog(ApiAutomationExecution execution, String code, String message, Instant occurredAt) {
        insertExecutionLog(UlidCreator.getMonotonicUlid().toString(), execution, "CONTROL_PLANE", null, "INFO", code, message,
                null, occurredAt);
    }

    public void appendRunnerLog(ApiAutomationExecution execution, ApiRunnerProtocol.ApiRunnerLog log,
                                String streamRecordId) {
        insertExecutionLog(UlidCreator.getMonotonicUlid().toString(), execution, "RUNNER", log.runnerId(), log.level(), log.code(),
                log.message(), streamRecordId, log.occurredAt());
    }

    public void appendRunnerHttpResult(ApiAutomationExecution execution, ApiRunnerProtocol.ApiRunnerHttpResult result,
                                       String streamRecordId) {
        jdbc.sql("""
                INSERT IGNORE INTO api_automation_execution_http_result
                    (id, execution_id, organization_id, project_id, step_id, request_sequence, attempt_number, method, outcome,
                     status_code, duration_millis, runner_id, stream_record_id, occurred_at)
                VALUES (:id, :executionId, :organizationId, :projectId, :stepId, :requestSequence, :attemptNumber, :method, :outcome,
                        NULLIF(:statusCode, 0), :durationMillis, :runnerId, :streamRecordId, :occurredAt)
                """).param("id", UlidCreator.getUlid().toString()).param("executionId", execution.id())
                .param("organizationId", execution.organizationId()).param("projectId", execution.projectId())
                .param("stepId", result.stepId()).param("requestSequence", result.requestSequence())
                .param("attemptNumber", result.attemptNumber())
                .param("method", result.method()).param("outcome", result.outcome()).param("statusCode", result.statusCode())
                .param("durationMillis", result.durationMillis()).param("runnerId", result.runnerId())
                .param("streamRecordId", streamRecordId).param("occurredAt", Timestamp.from(result.occurredAt())).update();
    }

    public void appendRunnerStepResult(ApiAutomationExecution execution, ApiRunnerProtocol.ApiRunnerStepResult result,
                                       String streamRecordId) {
        jdbc.sql("""
                INSERT IGNORE INTO api_automation_execution_step_result
                    (id, execution_id, organization_id, project_id, step_id, step_sequence, step_type, outcome,
                     observed_status_code, runner_id, stream_record_id, occurred_at)
                VALUES (:id, :executionId, :organizationId, :projectId, :stepId, :stepSequence, :stepType, :outcome,
                        :observedStatusCode, :runnerId, :streamRecordId, :occurredAt)
                """).param("id", UlidCreator.getUlid().toString()).param("executionId", execution.id())
                .param("organizationId", execution.organizationId()).param("projectId", execution.projectId())
                .param("stepId", result.stepId()).param("stepSequence", result.stepSequence())
                .param("stepType", result.type()).param("outcome", result.outcome())
                .param("observedStatusCode", result.observedStatusCode()).param("runnerId", result.runnerId())
                .param("streamRecordId", streamRecordId).param("occurredAt", Timestamp.from(result.occurredAt())).update();
    }

    public void appendRunnerScriptResult(ApiAutomationExecution execution, ApiRunnerProtocol.ApiRunnerScriptResult result,
                                         String streamRecordId) {
        jdbc.sql("""
                INSERT IGNORE INTO api_automation_execution_script_result
                    (id, execution_id, organization_id, project_id, script_phase, test_count, passed_count, failed_count,
                     runner_id, stream_record_id, occurred_at)
                VALUES (:id, :executionId, :organizationId, :projectId, :phase, :testCount, :passedCount, :failedCount,
                        :runnerId, :streamRecordId, :occurredAt)
                """).param("id", UlidCreator.getUlid().toString()).param("executionId", execution.id())
                .param("organizationId", execution.organizationId()).param("projectId", execution.projectId())
                .param("phase", result.phase()).param("testCount", result.testCount()).param("passedCount", result.passedCount())
                .param("failedCount", result.failedCount()).param("runnerId", result.runnerId())
                .param("streamRecordId", streamRecordId).param("occurredAt", Timestamp.from(result.occurredAt())).update();
    }

    public void insertExecution(String id, String taskId, String organizationId, String projectId, String scenarioId,
                                String scenarioName, int scenarioVersion, String executionProfileId,
                                Integer executionProfileVersion, String dataSetId, Integer dataSetRowNumber,
                                String manifestRef, String requestedBy, Instant requestedAt, Instant deadlineAt) {
        jdbc.sql("""
                INSERT INTO api_automation_execution
                    (id, task_id, organization_id, project_id, scenario_id, scenario_name, scenario_version, execution_profile_id,
                     execution_profile_version, data_set_id, data_set_row_number, status, manifest_ref, requested_by, requested_at, deadline_at)
                VALUES (:id, :taskId, :organizationId, :projectId, :scenarioId, :scenarioName, :scenarioVersion, :executionProfileId,
                        :executionProfileVersion, :dataSetId, :dataSetRowNumber, 'QUEUED', :manifestRef, :requestedBy, :requestedAt, :deadlineAt)
                """).param("id", id).param("taskId", taskId).param("organizationId", organizationId)
                .param("projectId", projectId).param("scenarioId", scenarioId).param("scenarioName", scenarioName)
                .param("scenarioVersion", scenarioVersion).param("executionProfileId", executionProfileId)
                .param("executionProfileVersion", executionProfileVersion).param("dataSetId", dataSetId)
                .param("dataSetRowNumber", dataSetRowNumber).param("manifestRef", manifestRef).param("requestedBy", requestedBy)
                .param("requestedAt", Timestamp.from(requestedAt)).param("deadlineAt", Timestamp.from(deadlineAt)).update();
    }

    public record ExecutionResultTotals(int httpResultCount, int httpSucceededCount, int httpFailedCount,
                                        long httpDurationMillis, int stepResultCount, int assertionPassedCount,
                                        int assertionFailedCount, int scriptResultCount, int scriptTestCount,
                                        int scriptPassedCount, int scriptFailedCount) {
    }

    public void insertManifest(String id, ApiAutomationExecution execution, ApiAutomationExecutionManifest.Snapshot snapshot,
                               String content, String checksum) {
        int secretReferences = (int) snapshot.variableBindings().stream().filter(ApiAutomationExecutionManifest.VariableBinding::secret).count();
        jdbc.sql("""
                INSERT INTO api_automation_execution_manifest
                    (id, execution_id, organization_id, project_id, scenario_id, scenario_version, content, checksum,
                     step_count, variable_count, secret_reference_count)
                VALUES (:id, :executionId, :organizationId, :projectId, :scenarioId, :scenarioVersion,
                        CAST(:content AS JSON), :checksum, :stepCount, :variableCount, :secretReferenceCount)
                """).param("id", id).param("executionId", execution.id()).param("organizationId", execution.organizationId())
                .param("projectId", execution.projectId()).param("scenarioId", execution.scenarioId())
                .param("scenarioVersion", execution.scenarioVersion()).param("content", content).param("checksum", checksum)
                .param("stepCount", snapshot.steps().size()).param("variableCount", snapshot.variableBindings().size())
                .param("secretReferenceCount", secretReferences).update();
    }

    public void insertRuntimeSnapshot(String id, ApiAutomationExecution execution,
                                      ApiAutomationExecutionRuntimeSnapshot.Snapshot snapshot, String content,
                                      String checksum) {
        jdbc.sql("""
                INSERT INTO api_automation_execution_runtime_snapshot
                    (id, execution_id, organization_id, project_id, content, checksum, variable_count, withheld_secret_count)
                VALUES (:id, :executionId, :organizationId, :projectId, CAST(:content AS JSON), :checksum,
                        :variableCount, :withheldSecretCount)
                """).param("id", id).param("executionId", execution.id()).param("organizationId", execution.organizationId())
                .param("projectId", execution.projectId()).param("content", content).param("checksum", checksum)
                .param("variableCount", snapshot.variables().size())
                .param("withheldSecretCount", snapshot.withheldSecretNames().size()).update();
    }

    public void insertOutbox(String id, String executionId, String messageType, String streamKey, Map<String, String> payload) {
        jdbc.sql("""
                INSERT INTO api_runner_outbox (id, execution_id, message_type, stream_key, payload, status)
                VALUES (:id, :executionId, :messageType, :streamKey, CAST(:payload AS JSON), 'PENDING')
                """).param("id", id).param("executionId", executionId).param("streamKey", streamKey)
                .param("messageType", messageType).param("payload", json(payload)).update();
    }

    public List<ApiRunnerOutboxMessage> findDispatchable(int limit) {
        return jdbc.sql("""
                SELECT id, execution_id, message_type, stream_key, payload, dispatch_attempts, max_dispatch_attempts
                FROM api_runner_outbox
                WHERE status = 'PENDING' AND available_at <= CURRENT_TIMESTAMP(3)
                ORDER BY created_at ASC LIMIT :limit
        """).param("limit", limit).query((rs, row) -> new ApiRunnerOutboxMessage(rs.getString("id"),
                rs.getString("execution_id"), rs.getString("message_type"), rs.getString("stream_key"),
                payload(rs.getString("payload")), rs.getInt("dispatch_attempts"), rs.getInt("max_dispatch_attempts"))).list();
    }

    public int claimOutbox(String id) {
        return jdbc.sql("""
                UPDATE api_runner_outbox SET status = 'DISPATCHING', dispatch_attempts = dispatch_attempts + 1,
                    locked_at = CURRENT_TIMESTAMP(3), updated_at = CURRENT_TIMESTAMP(3)
                WHERE id = :id AND status = 'PENDING' AND available_at <= CURRENT_TIMESTAMP(3)
                    AND dispatch_attempts < max_dispatch_attempts
                """).param("id", id).update();
    }

    public void markOutboxSent(String id) {
        jdbc.sql("""
                UPDATE api_runner_outbox SET status = 'SENT', sent_at = CURRENT_TIMESTAMP(3), locked_at = NULL,
                    last_error = NULL, updated_at = CURRENT_TIMESTAMP(3)
                WHERE id = :id AND status = 'DISPATCHING'
                """).param("id", id).update();
    }

    public void rescheduleOutbox(String id, String error) {
        jdbc.sql("""
                UPDATE api_runner_outbox SET
                    status = CASE WHEN dispatch_attempts >= max_dispatch_attempts THEN 'FAILED' ELSE 'PENDING' END,
                    available_at = CASE WHEN dispatch_attempts >= max_dispatch_attempts THEN available_at
                        ELSE DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 5 SECOND) END,
                    locked_at = NULL, last_error = :error, updated_at = CURRENT_TIMESTAMP(3)
                WHERE id = :id AND status = 'DISPATCHING'
                """).param("id", id).param("error", error).update();
    }

    public void releaseStaleOutboxClaims() {
        jdbc.sql("""
                UPDATE api_runner_outbox
                SET status = CASE WHEN dispatch_attempts >= max_dispatch_attempts THEN 'FAILED' ELSE 'PENDING' END,
                    locked_at = NULL, updated_at = CURRENT_TIMESTAMP(3)
                WHERE status = 'DISPATCHING' AND locked_at < DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE)
                """).update();
    }

    public int cancelBeforeDispatch(String projectId, String executionId) {
        return cancellation.cancel(PendingExecutionCancellation.Engine.API, projectId, executionId);
    }

    public int markCancelling(String projectId, String executionId) {
        return jdbc.sql("""
                UPDATE api_automation_execution
                SET status = 'CANCELLING', status_code = 'CANCEL_REQUESTED',
                    status_message = '已请求 Runner 取消任务。', updated_at = CURRENT_TIMESTAMP(3)
                WHERE project_id = :projectId AND id = :executionId
                    AND status IN ('QUEUED', 'ACCEPTED', 'RUNNING')
                """).param("projectId", projectId).param("executionId", executionId).update();
    }

    public List<ApiAutomationExecution> findExpiredActive() {
        return jdbc.sql("""
                SELECT id, task_id, organization_id, project_id, scenario_id, scenario_name, scenario_version, execution_profile_id,
                       execution_profile_version, data_set_id, data_set_row_number, status, status_code, status_message, manifest_ref, runner_id, requested_by, requested_at, deadline_at,
                       started_at, finished_at, created_at, updated_at
                FROM api_automation_execution
                WHERE status IN (%s) AND deadline_at <= CURRENT_TIMESTAMP(3)
                ORDER BY deadline_at ASC LIMIT 100
                """.formatted(ACTIVE_STATUSES)).query((rs, row) -> execution(rs)).list();
    }

    public int markTimedOut(String executionId) {
        return jdbc.sql("""
                UPDATE api_automation_execution
                SET status = 'TIMED_OUT', status_code = 'EXECUTION_TIMEOUT',
                    status_message = '任务已达到截止时间，已停止等待 Runner 结果。',
                    finished_at = CURRENT_TIMESTAMP(3), updated_at = CURRENT_TIMESTAMP(3)
                WHERE id = :executionId AND status IN (%s) AND deadline_at <= CURRENT_TIMESTAMP(3)
                """.formatted(ACTIVE_STATUSES)).param("executionId", executionId).update();
    }

    public void cancelPendingTaskOutbox(String executionId) {
        jdbc.sql("""
                UPDATE api_runner_outbox
                SET status = 'CANCELLED', locked_at = NULL, last_error = NULL, updated_at = CURRENT_TIMESTAMP(3)
                WHERE execution_id = :executionId AND message_type = 'TASK' AND status = 'PENDING'
                """).param("executionId", executionId).update();
    }

    public boolean hasTaskPossiblyDispatched(String executionId) {
        Integer count = jdbc.sql("""
                SELECT COUNT(*) FROM api_runner_outbox
                WHERE execution_id = :executionId AND message_type = 'TASK' AND status IN ('DISPATCHING', 'SENT')
                """).param("executionId", executionId).query(Integer.class).single();
        return count != null && count > 0;
    }

    public boolean hasActiveCancellationCommand(String executionId) {
        Integer count = jdbc.sql("""
                SELECT COUNT(*) FROM api_runner_outbox
                WHERE execution_id = :executionId AND message_type = 'CANCEL' AND status IN ('PENDING', 'DISPATCHING', 'SENT')
                """).param("executionId", executionId).query(Integer.class).single();
        return count != null && count > 0;
    }

    public List<ApiAutomationExecution> finalizeExhaustedDeliveries() {
        List<DeliveryFailure> candidates = jdbc.sql("""
                SELECT execution.id, execution.project_id, outbox.message_type
                FROM api_automation_execution execution
                JOIN api_runner_outbox outbox ON outbox.execution_id = execution.id
                WHERE execution.status IN (%s) AND outbox.status = 'FAILED'
                ORDER BY outbox.updated_at ASC LIMIT 100
                """.formatted(ACTIVE_STATUSES)).query((rs, row) -> new DeliveryFailure(rs.getString("id"),
                rs.getString("project_id"), rs.getString("message_type"))).list();
        List<ApiAutomationExecution> failed = new ArrayList<>();
        for (DeliveryFailure candidate : candidates) {
            int updated = jdbc.sql("""
                    UPDATE api_automation_execution
                    SET status = 'FAILED',
                        status_code = :code,
                        status_message = :message,
                        finished_at = CURRENT_TIMESTAMP(3), updated_at = CURRENT_TIMESTAMP(3)
                    WHERE id = :executionId AND project_id = :projectId AND status IN (%s)
                    """.formatted(ACTIVE_STATUSES)).param("executionId", candidate.executionId())
                    .param("projectId", candidate.projectId()).param("code", deliveryFailureCode(candidate.messageType()))
                    .param("message", deliveryFailureMessage(candidate.messageType())).update();
            if (updated > 0) findById(candidate.projectId(), candidate.executionId()).ifPresent(failed::add);
        }
        return failed;
    }

    public int applyRunnerEvent(String taskId, String executionId, String status, String code, String message,
                                String runnerId, Instant occurredAt) {
        String sql = """
                UPDATE api_automation_execution
                SET status = :status, status_code = :code, status_message = :message, runner_id = :runnerId,
                    started_at = CASE WHEN :status IN ('ACCEPTED', 'RUNNING') AND started_at IS NULL THEN :occurredAt ELSE started_at END,
                    finished_at = CASE WHEN :status IN (%s) THEN :occurredAt ELSE finished_at END,
                    updated_at = CURRENT_TIMESTAMP(3)
                WHERE task_id = :taskId AND id = :executionId
                  AND (status NOT IN (%s) OR status = :status)
                """.formatted(TERMINAL_STATUSES, TERMINAL_STATUSES);
        return jdbc.sql(sql).param("taskId", taskId).param("executionId", executionId).param("status", status)
                .param("code", code).param("message", message).param("runnerId", runnerId)
                .param("occurredAt", Timestamp.from(occurredAt)).update();
    }

    public String json(Object source) {
        try {
            return json.writeValueAsString(source);
        } catch (Exception exception) {
            throw new IllegalStateException("Runner Outbox 消息无法序列化", exception);
        }
    }

    private Map<String, String> payload(String source) {
        try {
            Map<String, String> values = json.readValue(source, new TypeReference<>() { });
            return Map.copyOf(new LinkedHashMap<>(values));
        } catch (Exception exception) {
            throw new IllegalStateException("Runner Outbox 消息格式不合法", exception);
        }
    }

    private ApiAutomationExecution execution(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ApiAutomationExecution(rs.getString("id"), rs.getString("task_id"), rs.getString("organization_id"),
                rs.getString("project_id"), rs.getString("scenario_id"), rs.getString("scenario_name"),
                rs.getInt("scenario_version"), rs.getString("execution_profile_id"),
                rs.getObject("execution_profile_version", Integer.class), rs.getString("data_set_id"),
                rs.getObject("data_set_row_number", Integer.class), rs.getString("status"), rs.getString("status_code"),
                rs.getString("status_message"), rs.getString("manifest_ref"), rs.getString("runner_id"),
                rs.getString("requested_by"), instant(rs.getTimestamp("requested_at")), instant(rs.getTimestamp("deadline_at")),
                instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("finished_at")),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
    }

    private ApiAutomationExecutionLog executionLog(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ApiAutomationExecutionLog(rs.getString("id"), rs.getString("execution_id"), rs.getString("source"),
                rs.getString("runner_id"), rs.getString("level"), rs.getString("code"), rs.getString("message"),
                instant(rs.getTimestamp("occurred_at")), instant(rs.getTimestamp("created_at")));
    }

    private ApiAutomationExecutionManifest.Snapshot manifestSnapshot(String source) {
        try {
            return json.readValue(source, ApiAutomationExecutionManifest.Snapshot.class);
        } catch (Exception exception) {
            throw new IllegalStateException("已保存的接口自动化执行清单格式不合法", exception);
        }
    }

    private ApiAutomationExecutionRuntimeSnapshot.Snapshot runtimeSnapshot(String source) {
        try {
            return json.readValue(source, ApiAutomationExecutionRuntimeSnapshot.Snapshot.class);
        } catch (Exception exception) {
            throw new IllegalStateException("已保存的接口自动化运行时变量快照格式不合法", exception);
        }
    }

    private void insertExecutionLog(String id, ApiAutomationExecution execution, String source, String runnerId,
                                    String level, String code, String message, String streamRecordId, Instant occurredAt) {
        jdbc.sql("""
                INSERT IGNORE INTO api_automation_execution_log
                    (id, execution_id, organization_id, project_id, source, runner_id, level, code, message,
                     stream_record_id, occurred_at)
                VALUES (:id, :executionId, :organizationId, :projectId, :source, :runnerId, :level, :code, :message,
                        :streamRecordId, :occurredAt)
                """).param("id", id).param("executionId", execution.id()).param("organizationId", execution.organizationId())
                .param("projectId", execution.projectId()).param("source", source).param("runnerId", runnerId)
                .param("level", level).param("code", code).param("message", message).param("streamRecordId", streamRecordId)
                .param("occurredAt", Timestamp.from(occurredAt)).update();
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private String deliveryFailureCode(String messageType) {
        return "CANCEL".equals(messageType) ? "CANCELLATION_DELIVERY_FAILED" : "DELIVERY_RETRY_EXHAUSTED";
    }

    private String deliveryFailureMessage(String messageType) {
        return "CANCEL".equals(messageType)
                ? "取消指令多次投递失败，无法确认 Runner 已停止任务。"
                : "任务多次投递失败，已停止重试。";
    }

    private record DeliveryFailure(String executionId, String projectId, String messageType) {
    }
}
