package io.testhub.platform.uiautomation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import java.sql.Timestamp;
import io.testhub.platform.shared.persistence.PendingExecutionCancellation;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UiAutomationRepository {
    public void lockProject(String projectId) {
        jdbc.sql("SELECT id FROM project WHERE id=:project FOR UPDATE").param("project", projectId).query(String.class).single();
    }
    private static final TypeReference<Map<String, String>> VARIABLES = new TypeReference<>() { };
    private static final TypeReference<List<UiAutomationStep>> STEPS = new TypeReference<>() { };
    private final JdbcClient jdbc;
    private final PendingExecutionCancellation cancellation;
    private final ObjectMapper json;

    public UiAutomationRepository(JdbcClient jdbc, ObjectMapper json, PendingExecutionCancellation cancellation) {
        this.jdbc = jdbc;
        this.cancellation = cancellation;
        this.json = json;
    }

    public List<UiAutomationBrowserProfile> findProfiles(String projectId) {
        return jdbc.sql(profileSelect() + " WHERE project_id = :projectId ORDER BY updated_at DESC, name")
                .param("projectId", projectId).query((rs, row) -> profile(rs)).list();
    }

    public Optional<UiAutomationBrowserProfile> findProfile(String projectId, String id) {
        return jdbc.sql(profileSelect() + " WHERE project_id = :projectId AND id = :id").param("projectId", projectId)
                .param("id", id).query((rs, row) -> profile(rs)).optional();
    }

    public void insertProfile(String id, String organizationId, String projectId, ProfileInput input, String userId) {
        jdbc.sql("""
                INSERT INTO ui_automation_browser_profile
                    (id, organization_id, project_id, name, browser, base_url, viewport_width, viewport_height, enabled,
                     created_by, updated_by)
                VALUES (:id, :organizationId, :projectId, :name, :browser, :baseUrl, :viewportWidth, :viewportHeight,
                        :enabled, :userId, :userId)
                """).param("id", id).param("organizationId", organizationId).param("projectId", projectId)
                .param("name", input.name()).param("browser", input.browser()).param("baseUrl", input.baseUrl())
                .param("viewportWidth", input.viewportWidth()).param("viewportHeight", input.viewportHeight())
                .param("enabled", input.enabled()).param("userId", userId).update();
    }

    public int updateProfile(String projectId, String id, ProfileInput input, String userId) {
        return jdbc.sql("""
                UPDATE ui_automation_browser_profile
                SET name = :name, browser = :browser, base_url = :baseUrl, viewport_width = :viewportWidth,
                    viewport_height = :viewportHeight, enabled = :enabled, version = version + 1, updated_by = :userId,
                    updated_at = CURRENT_TIMESTAMP(3)
                WHERE project_id = :projectId AND id = :id
                """).param("projectId", projectId).param("id", id).param("name", input.name()).param("browser", input.browser())
                .param("baseUrl", input.baseUrl()).param("viewportWidth", input.viewportWidth())
                .param("viewportHeight", input.viewportHeight()).param("enabled", input.enabled()).param("userId", userId).update();
    }

    public void deleteProfile(String projectId, String id) {
        jdbc.sql("DELETE FROM ui_automation_browser_profile WHERE project_id = :projectId AND id = :id")
                .param("projectId", projectId).param("id", id).update();
    }

    public List<UiAutomationPageObject> findPageObjects(String projectId) {
        return jdbc.sql(pageObjectSelect() + " WHERE project_id = :projectId ORDER BY updated_at DESC, name")
                .param("projectId", projectId).query((rs, row) -> pageObject(rs)).list();
    }

    public Optional<UiAutomationPageObject> findPageObject(String projectId, String id) {
        return jdbc.sql(pageObjectSelect() + " WHERE project_id = :projectId AND id = :id").param("projectId", projectId)
                .param("id", id).query((rs, row) -> pageObject(rs)).optional();
    }

    public void insertPageObject(String id, String organizationId, String projectId, PageObjectInput input, String userId) {
        jdbc.sql("""
                INSERT INTO ui_automation_page_object
                    (id, organization_id, project_id, name, selector_value, description, created_by, updated_by)
                VALUES (:id, :organizationId, :projectId, :name, :selector, :description, :userId, :userId)
                """).param("id", id).param("organizationId", organizationId).param("projectId", projectId)
                .param("name", input.name()).param("selector", input.selectorValue()).param("description", input.description())
                .param("userId", userId).update();
    }

    public int updatePageObject(String projectId, String id, PageObjectInput input, String userId) {
        return jdbc.sql("""
                UPDATE ui_automation_page_object
                SET name = :name, selector_value = :selector, description = :description, updated_by = :userId,
                    updated_at = CURRENT_TIMESTAMP(3)
                WHERE project_id = :projectId AND id = :id
                """).param("projectId", projectId).param("id", id).param("name", input.name())
                .param("selector", input.selectorValue()).param("description", input.description()).param("userId", userId).update();
    }

    public void deletePageObject(String projectId, String id) {
        jdbc.sql("DELETE FROM ui_automation_page_object WHERE project_id = :projectId AND id = :id")
                .param("projectId", projectId).param("id", id).update();
    }

    public boolean isPageObjectReferenced(String projectId, String id) {
        Integer count = jdbc.sql("""
                SELECT COUNT(*) FROM ui_automation_scenario
                WHERE project_id = :projectId
                  AND JSON_SEARCH(steps, 'one', :id, NULL, '$[*].pageObjectId') IS NOT NULL
                """).param("projectId", projectId).param("id", id).query(Integer.class).single();
        return count != null && count > 0;
    }

    public List<UiAutomationScenario> findScenarios(String projectId) {
        return jdbc.sql(scenarioSelect() + " WHERE project_id = :projectId ORDER BY updated_at DESC, name")
                .param("projectId", projectId).query((rs, row) -> scenario(rs)).list();
    }

    public Optional<UiAutomationScenario> findScenario(String projectId, String id) {
        return jdbc.sql(scenarioSelect() + " WHERE project_id = :projectId AND id = :id").param("projectId", projectId)
                .param("id", id).query((rs, row) -> scenario(rs)).optional();
    }

    public void insertScenario(String id, String organizationId, String projectId, ScenarioInput input, String userId) {
        jdbc.sql("""
                INSERT INTO ui_automation_scenario
                    (id, organization_id, project_id, browser_profile_id, name, description, failure_strategy, variables, steps,
                     created_by, updated_by)
                VALUES (:id, :organizationId, :projectId, :browserProfileId, :name, :description, :failureStrategy,
                        CAST(:variables AS JSON), CAST(:steps AS JSON), :userId, :userId)
                """).param("id", id).param("organizationId", organizationId).param("projectId", projectId)
                .param("browserProfileId", input.browserProfileId()).param("name", input.name()).param("description", input.description())
                .param("failureStrategy", input.failureStrategy()).param("variables", write(input.variables())).param("steps", write(input.steps()))
                .param("userId", userId).update();
    }

    public int updateScenario(String projectId, String id, ScenarioInput input, String userId) {
        return jdbc.sql("""
                UPDATE ui_automation_scenario
                SET browser_profile_id = :browserProfileId, name = :name, description = :description,
                    failure_strategy = :failureStrategy, variables = CAST(:variables AS JSON), steps = CAST(:steps AS JSON),
                    version = version + 1, updated_by = :userId, updated_at = CURRENT_TIMESTAMP(3)
                WHERE project_id = :projectId AND id = :id
                """).param("projectId", projectId).param("id", id).param("browserProfileId", input.browserProfileId())
                .param("name", input.name()).param("description", input.description()).param("failureStrategy", input.failureStrategy())
                .param("variables", write(input.variables())).param("steps", write(input.steps())).param("userId", userId).update();
    }

    public void deleteScenario(String projectId, String id) {
        jdbc.sql("DELETE FROM ui_automation_scenario WHERE project_id = :projectId AND id = :id")
                .param("projectId", projectId).param("id", id).update();
    }

    public List<UiAutomationExecution> findExecutions(String projectId) {
        return jdbc.sql("SELECT " + executionColumns() + " FROM ui_automation_execution WHERE project_id = :projectId "
                        + "ORDER BY requested_at DESC, id DESC LIMIT 100").param("projectId", projectId)
                .query((rs, row) -> execution(rs)).list();
    }

    public Optional<UiAutomationExecution> findExecution(String projectId, String id) {
        return jdbc.sql("SELECT " + executionColumns() + " FROM ui_automation_execution WHERE project_id = :projectId AND id = :id")
                .param("projectId", projectId).param("id", id).query((rs, row) -> execution(rs)).optional();
    }

    public Optional<UiAutomationExecution> findExecutionByTask(String taskId, String executionId) {
        return jdbc.sql("SELECT " + executionColumns() + " FROM ui_automation_execution WHERE task_id = :taskId AND id = :executionId")
                .param("taskId", taskId).param("executionId", executionId).query((rs, row) -> execution(rs)).optional();
    }

    public void insertExecution(String id, String taskId, String organizationId, String projectId, UiAutomationScenario scenario,
                                UiAutomationBrowserProfile profile, String scheduleId, String functionalPlanId,
                                String functionalPlanName, String retryOfExecutionId, int retryAttempt, String manifestRef,
                                String requestedBy, Instant requestedAt, Instant deadlineAt) {
        jdbc.sql("""
                INSERT INTO ui_automation_execution
                    (id, task_id, organization_id, project_id, scenario_id, scenario_name, scenario_version, browser_profile_id,
                     browser_profile_name, browser, schedule_id, functional_plan_id, functional_plan_name, retry_of_execution_id,
                     retry_attempt, manifest_ref, requested_by, requested_at, deadline_at)
                VALUES (:id, :taskId, :organizationId, :projectId, :scenarioId, :scenarioName, :scenarioVersion, :profileId,
                        :profileName, :browser, :scheduleId, :functionalPlanId, :functionalPlanName, :retryOfExecutionId,
                        :retryAttempt, :manifestRef, :requestedBy, :requestedAt, :deadlineAt)
                """).param("id", id).param("taskId", taskId).param("organizationId", organizationId).param("projectId", projectId)
                .param("scenarioId", scenario.id()).param("scenarioName", scenario.name()).param("scenarioVersion", scenario.version())
                .param("profileId", profile.id()).param("profileName", profile.name()).param("browser", profile.browser())
                .param("scheduleId", scheduleId).param("functionalPlanId", functionalPlanId).param("functionalPlanName", functionalPlanName)
                .param("retryOfExecutionId", retryOfExecutionId).param("retryAttempt", retryAttempt)
                .param("manifestRef", manifestRef).param("requestedBy", requestedBy).param("requestedAt", Timestamp.from(requestedAt))
                .param("deadlineAt", Timestamp.from(deadlineAt)).update();
    }

    public void insertManifest(String id, UiAutomationExecution execution, UiAutomationManifest.Snapshot snapshot, String content,
                               String checksum) {
        jdbc.sql("""
                INSERT INTO ui_automation_execution_manifest
                    (id, execution_id, organization_id, project_id, scenario_id, scenario_version, content, checksum)
                VALUES (:id, :executionId, :organizationId, :projectId, :scenarioId, :scenarioVersion, CAST(:content AS JSON), :checksum)
                """).param("id", id).param("executionId", execution.id()).param("organizationId", execution.organizationId())
                .param("projectId", execution.projectId()).param("scenarioId", execution.scenarioId())
                .param("scenarioVersion", execution.scenarioVersion()).param("content", content).param("checksum", checksum).update();
    }

    public Optional<UiAutomationManifest> findManifest(String projectId, String executionId) {
        return jdbc.sql("""
                SELECT id, execution_id, scenario_id, scenario_version, content, checksum, created_at
                FROM ui_automation_execution_manifest WHERE project_id = :projectId AND execution_id = :executionId
                """).param("projectId", projectId).param("executionId", executionId).query((rs, row) -> new UiAutomationManifest(
                rs.getString("id"), rs.getString("execution_id"), rs.getString("scenario_id"), rs.getInt("scenario_version"),
                read(rs.getString("content"), UiAutomationManifest.Snapshot.class), rs.getString("checksum"), instant(rs.getTimestamp("created_at")))).optional();
    }

    public List<UiAutomationExecutionStepResult> findStepResults(String projectId, String executionId) {
        return jdbc.sql("""
                SELECT id, execution_id, runner_id, step_id, step_sequence, step_type, step_name, outcome, duration_millis,
                       error_message, occurred_at
                FROM ui_automation_execution_step_result WHERE project_id = :projectId AND execution_id = :executionId
                ORDER BY step_sequence, occurred_at, id
                """).param("projectId", projectId).param("executionId", executionId).query((rs, row) -> new UiAutomationExecutionStepResult(
                rs.getString("id"), rs.getString("execution_id"), rs.getString("runner_id"), rs.getString("step_id"),
                rs.getInt("step_sequence"), rs.getString("step_type"), rs.getString("step_name"), rs.getString("outcome"),
                rs.getInt("duration_millis"), rs.getString("error_message"), instant(rs.getTimestamp("occurred_at")))).list();
    }

    public void insertStepResult(UiAutomationExecution execution, UiAutomationRunnerProtocol.StepResult result) {
        jdbc.sql("""
                INSERT INTO ui_automation_execution_step_result
                    (id, execution_id, organization_id, project_id, runner_id, step_id, step_sequence, step_type, step_name,
                     outcome, duration_millis, error_message, occurred_at)
                VALUES (:id, :executionId, :organizationId, :projectId, :runnerId, :stepId, :stepSequence, :stepType, :stepName,
                        :outcome, :durationMillis, :errorMessage, :occurredAt)
                ON DUPLICATE KEY UPDATE runner_id = VALUES(runner_id), step_type = VALUES(step_type), step_name = VALUES(step_name),
                    outcome = VALUES(outcome), duration_millis = VALUES(duration_millis), error_message = VALUES(error_message),
                    occurred_at = VALUES(occurred_at)
                """).param("id", UlidCreator.getUlid().toString()).param("executionId", execution.id())
                .param("organizationId", execution.organizationId()).param("projectId", execution.projectId())
                .param("runnerId", result.runnerId()).param("stepId", result.stepId()).param("stepSequence", result.stepSequence())
                .param("stepType", result.stepType()).param("stepName", result.stepName()).param("outcome", result.outcome())
                .param("durationMillis", result.durationMillis()).param("errorMessage", result.errorMessage())
                .param("occurredAt", Timestamp.from(result.occurredAt())).update();
    }

    public List<UiAutomationExecutionArtifact> findArtifacts(String projectId, String executionId) {
        return jdbc.sql("""
                SELECT id, execution_id, artifact_type, original_name, content_type, size_bytes, checksum, runner_id, created_at
                FROM ui_automation_execution_artifact WHERE project_id = :projectId AND execution_id = :executionId
                ORDER BY created_at, id
                """).param("projectId", projectId).param("executionId", executionId).query((rs, row) -> artifact(rs)).list();
    }

    public Optional<StoredArtifact> findArtifact(String projectId, String executionId, String id) {
        return jdbc.sql("""
                SELECT id, execution_id, project_id, object_key, artifact_type, original_name, content_type, size_bytes,
                       checksum, runner_id, created_at
                FROM ui_automation_execution_artifact
                WHERE project_id = :projectId AND execution_id = :executionId AND id = :id
                """).param("projectId", projectId).param("executionId", executionId).param("id", id).query((rs, row) ->
                new StoredArtifact(artifact(rs), rs.getString("project_id"), rs.getString("object_key"))).optional();
    }

    public Optional<StoredArtifact> findArtifactByChecksum(String executionId, String type, String checksum) {
        return jdbc.sql("""
                SELECT id, execution_id, project_id, object_key, artifact_type, original_name, content_type, size_bytes,
                       checksum, runner_id, created_at
                FROM ui_automation_execution_artifact
                WHERE execution_id = :executionId AND artifact_type = :type AND checksum = :checksum
                """).param("executionId", executionId).param("type", type).param("checksum", checksum).query((rs, row) ->
                new StoredArtifact(artifact(rs), rs.getString("project_id"), rs.getString("object_key"))).optional();
    }

    public void insertArtifact(String id, UiAutomationExecution execution, String type, String objectKey, String originalName,
                               String contentType, long sizeBytes, String checksum, String runnerId) {
        jdbc.sql("""
                INSERT INTO ui_automation_execution_artifact
                    (id, execution_id, organization_id, project_id, artifact_type, object_key, original_name, content_type,
                     size_bytes, checksum, runner_id)
                VALUES (:id, :executionId, :organizationId, :projectId, :type, :objectKey, :originalName, :contentType,
                        :sizeBytes, :checksum, :runnerId)
                """).param("id", id).param("executionId", execution.id()).param("organizationId", execution.organizationId())
                .param("projectId", execution.projectId()).param("type", type).param("objectKey", objectKey)
                .param("originalName", originalName).param("contentType", contentType).param("sizeBytes", sizeBytes)
                .param("checksum", checksum).param("runnerId", runnerId).update();
    }

    public void insertOutbox(String id, String executionId, String type, String streamKey, Map<String, String> payload) {
        insertOutbox(id, executionId, type, streamKey, payload, Instant.now());
    }

    public void insertOutbox(String id, String executionId, String type, String streamKey, Map<String, String> payload,
                             Instant availableAt) {
        jdbc.sql("""
                INSERT INTO ui_automation_runner_outbox (id, execution_id, message_type, stream_key, payload, status, available_at)
                VALUES (:id, :executionId, :type, :streamKey, CAST(:payload AS JSON), 'PENDING', :availableAt)
                """).param("id", id).param("executionId", executionId).param("type", type).param("streamKey", streamKey)
                .param("payload", write(payload)).param("availableAt", Timestamp.from(availableAt)).update();
    }

    public List<OutboxMessage> findDispatchable(int limit) {
        return jdbc.sql("""
                SELECT id, execution_id, message_type, stream_key, payload, dispatch_attempts, max_dispatch_attempts
                FROM ui_automation_runner_outbox WHERE status = 'PENDING' AND available_at <= CURRENT_TIMESTAMP(3)
                ORDER BY created_at LIMIT :limit
                """).param("limit", limit).query((rs, row) -> new OutboxMessage(rs.getString("id"), rs.getString("execution_id"),
                rs.getString("message_type"), rs.getString("stream_key"), read(rs.getString("payload"), new TypeReference<Map<String, String>>() { }),
                rs.getInt("dispatch_attempts"), rs.getInt("max_dispatch_attempts"))).list();
    }

    public int claimOutbox(String id) {
        return jdbc.sql("""
                UPDATE ui_automation_runner_outbox SET status = 'DISPATCHING', dispatch_attempts = dispatch_attempts + 1,
                    locked_at = CURRENT_TIMESTAMP(3), updated_at = CURRENT_TIMESTAMP(3)
                WHERE id = :id AND status = 'PENDING' AND available_at <= CURRENT_TIMESTAMP(3)
                    AND dispatch_attempts < max_dispatch_attempts
                """).param("id", id).update();
    }

    public void markOutboxSent(String id) {
        jdbc.sql("""
                UPDATE ui_automation_runner_outbox
                SET status = 'SENT', sent_at = CURRENT_TIMESTAMP(3), locked_at = NULL, last_error = NULL, updated_at = CURRENT_TIMESTAMP(3)
                WHERE id = :id AND status = 'DISPATCHING'
                """).param("id", id).update();
    }

    public void retryOutbox(String id, String error) {
        jdbc.sql("""
                UPDATE ui_automation_runner_outbox
                SET status = CASE WHEN dispatch_attempts >= max_dispatch_attempts THEN 'FAILED' ELSE 'PENDING' END,
                    available_at = CASE WHEN dispatch_attempts >= max_dispatch_attempts THEN available_at
                        ELSE DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 5 SECOND) END,
                    locked_at = NULL, last_error = :error, updated_at = CURRENT_TIMESTAMP(3)
                WHERE id = :id AND status = 'DISPATCHING'
                """).param("id", id).param("error", abbreviate(error)).update();
    }

    public void releaseStaleOutboxClaims() {
        jdbc.sql("""
                UPDATE ui_automation_runner_outbox SET status = CASE WHEN dispatch_attempts >= max_dispatch_attempts THEN 'FAILED' ELSE 'PENDING' END,
                    locked_at = NULL, updated_at = CURRENT_TIMESTAMP(3)
                WHERE status = 'DISPATCHING' AND locked_at < DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE)
                """).update();
    }

    public int applyRunnerEvent(UiAutomationRunnerProtocol.Event event, String status) {
        String terminal = "SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status) || "REJECTED".equals(status)
                ? ", finished_at = :occurredAt" : "";
        String started = "RUNNING".equals(status) ? ", started_at = COALESCE(started_at, :occurredAt)" : "";
        return jdbc.sql("UPDATE ui_automation_execution SET status = :status, status_code = :code, status_message = :message, "
                        + "runner_id = :runnerId, updated_at = CURRENT_TIMESTAMP(3)" + started + terminal
                        + " WHERE task_id = :taskId AND id = :executionId AND status IN ('QUEUED', 'ACCEPTED', 'RUNNING', 'CANCELLING')")
                .param("status", status).param("code", event.code()).param("message", event.message()).param("runnerId", event.runnerId())
                .param("occurredAt", Timestamp.from(event.occurredAt())).param("taskId", event.taskId()).param("executionId", event.executionId()).update();
    }

    public int markRuntimeClaimed(String taskId, String executionId, String runnerId) {
        return jdbc.sql("""
                UPDATE ui_automation_execution SET status = 'ACCEPTED', status_code = 'RUNTIME_CONFIGURATION_ISSUED',
                    status_message = '已向隔离 Playwright Runner 签发受控运行时工件。', runner_id = :runnerId,
                    updated_at = CURRENT_TIMESTAMP(3)
                WHERE task_id = :taskId AND id = :executionId AND status IN ('QUEUED', 'ACCEPTED')
                    AND (runner_id IS NULL OR runner_id = :runnerId)
                """).param("taskId", taskId).param("executionId", executionId).param("runnerId", runnerId).update();
    }

    public List<UiAutomationExecution> findExpiredActive() {
        return jdbc.sql("SELECT " + executionColumns() + " FROM ui_automation_execution WHERE status IN ('QUEUED','ACCEPTED','RUNNING','CANCELLING') "
                        + "AND deadline_at <= CURRENT_TIMESTAMP(3) ORDER BY deadline_at LIMIT 100").query((rs, row) -> execution(rs)).list();
    }

    public int markTimedOut(String id) {
        return jdbc.sql("""
                UPDATE ui_automation_execution SET status = 'TIMED_OUT', status_code = 'EXECUTION_TIMEOUT',
                    status_message = '执行超过配置的时间限制。', finished_at = CURRENT_TIMESTAMP(3), updated_at = CURRENT_TIMESTAMP(3)
                WHERE id = :id AND status IN ('QUEUED','ACCEPTED','RUNNING','CANCELLING')
                """).param("id", id).update();
    }

    public void cancelPendingTaskOutbox(String executionId) {
        jdbc.sql("""
                UPDATE ui_automation_runner_outbox SET status = 'CANCELLED', locked_at = NULL, updated_at = CURRENT_TIMESTAMP(3)
                WHERE execution_id = :executionId AND message_type = 'TASK' AND status = 'PENDING'
                """).param("executionId", executionId).update();
    }

    public int cancelBeforeDispatch(String projectId, String executionId) {
        return cancellation.cancel(PendingExecutionCancellation.Engine.UI, projectId, executionId);
    }

    public int markCancelling(String projectId, String executionId) {
        return jdbc.sql("""
                UPDATE ui_automation_execution
                SET status = 'CANCELLING', status_code = 'CANCEL_REQUESTED', status_message = '已请求 Runner 取消任务。',
                    updated_at = CURRENT_TIMESTAMP(3)
                WHERE project_id = :projectId AND id = :executionId AND status IN ('QUEUED', 'ACCEPTED', 'RUNNING')
                """).param("projectId", projectId).param("executionId", executionId).update();
    }

    public boolean hasTaskPossiblyDispatched(String executionId) {
        Integer count = jdbc.sql("""
                SELECT COUNT(*) FROM ui_automation_runner_outbox
                WHERE execution_id = :executionId AND message_type = 'TASK' AND status IN ('DISPATCHING', 'SENT')
                """).param("executionId", executionId).query(Integer.class).single();
        return count != null && count > 0;
    }

    public boolean hasActiveCancellationCommand(String executionId) {
        Integer count = jdbc.sql("""
                SELECT COUNT(*) FROM ui_automation_runner_outbox
                WHERE execution_id = :executionId AND message_type = 'CANCEL' AND status IN ('PENDING', 'DISPATCHING', 'SENT')
                """).param("executionId", executionId).query(Integer.class).single();
        return count != null && count > 0;
    }

    private String profileSelect() {
        return "SELECT id, organization_id, project_id, name, browser, base_url, viewport_width, viewport_height, enabled, version, "
                + "updated_by, created_at, updated_at FROM ui_automation_browser_profile";
    }
    private String pageObjectSelect() {
        return "SELECT id, organization_id, project_id, name, selector_value, description, updated_by, created_at, updated_at "
                + "FROM ui_automation_page_object";
    }
    private String scenarioSelect() {
        return "SELECT id, organization_id, project_id, browser_profile_id, name, description, failure_strategy, version, variables, steps, "
                + "updated_by, created_at, updated_at FROM ui_automation_scenario";
    }
    private String executionColumns() {
        return "id, task_id, organization_id, project_id, scenario_id, scenario_name, scenario_version, browser_profile_id, "
                + "browser_profile_name, browser, schedule_id, functional_plan_id, functional_plan_name, retry_of_execution_id, retry_attempt, "
                + "status, status_code, status_message, runner_id, manifest_ref, requested_by, requested_at, "
                + "deadline_at, started_at, finished_at, created_at, updated_at";
    }
    private UiAutomationBrowserProfile profile(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new UiAutomationBrowserProfile(rs.getString("id"), rs.getString("organization_id"), rs.getString("project_id"),
                rs.getString("name"), rs.getString("browser"), rs.getString("base_url"), rs.getInt("viewport_width"),
                rs.getInt("viewport_height"), rs.getBoolean("enabled"), rs.getInt("version"), rs.getString("updated_by"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
    }
    private UiAutomationPageObject pageObject(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new UiAutomationPageObject(rs.getString("id"), rs.getString("organization_id"), rs.getString("project_id"),
                rs.getString("name"), rs.getString("selector_value"), rs.getString("description"), rs.getString("updated_by"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
    }
    private UiAutomationScenario scenario(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new UiAutomationScenario(rs.getString("id"), rs.getString("organization_id"), rs.getString("project_id"),
                rs.getString("browser_profile_id"), rs.getString("name"), rs.getString("description"), rs.getString("failure_strategy"),
                rs.getInt("version"), read(rs.getString("variables"), VARIABLES), read(rs.getString("steps"), STEPS),
                rs.getString("updated_by"), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
    }
    private UiAutomationExecution execution(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new UiAutomationExecution(rs.getString("id"), rs.getString("task_id"), rs.getString("organization_id"),
                rs.getString("project_id"), rs.getString("scenario_id"), rs.getString("scenario_name"), rs.getInt("scenario_version"),
                rs.getString("browser_profile_id"), rs.getString("browser_profile_name"), rs.getString("browser"),
                rs.getString("schedule_id"), rs.getString("functional_plan_id"), rs.getString("functional_plan_name"),
                rs.getString("retry_of_execution_id"), rs.getInt("retry_attempt"), rs.getString("status"),
                rs.getString("status_code"), rs.getString("status_message"), rs.getString("runner_id"),
                rs.getString("manifest_ref"), rs.getString("requested_by"), instant(rs.getTimestamp("requested_at")),
                instant(rs.getTimestamp("deadline_at")), instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("finished_at")),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
    }
    private UiAutomationExecutionArtifact artifact(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new UiAutomationExecutionArtifact(rs.getString("id"), rs.getString("execution_id"), rs.getString("artifact_type"),
                rs.getString("original_name"), rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("checksum"),
                rs.getString("runner_id"), instant(rs.getTimestamp("created_at")));
    }
    private Instant instant(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant(); }
    private String write(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception exception) { throw new IllegalStateException("无法保存 Web UI 自动化数据", exception); }
    }
    private <T> T read(String value, Class<T> type) {
        try { return json.readValue(value, type); } catch (Exception exception) { throw new IllegalStateException("Web UI 自动化数据损坏", exception); }
    }
    private <T> T read(String value, TypeReference<T> type) {
        try { return json.readValue(value, type); } catch (Exception exception) { throw new IllegalStateException("Web UI 自动化数据损坏", exception); }
    }
    private String abbreviate(String value) { return value == null ? "Runner Stream 投递失败" : value.substring(0, Math.min(512, value.length())); }

    public record ProfileInput(String name, String browser, String baseUrl, int viewportWidth, int viewportHeight, boolean enabled) { }
    public record PageObjectInput(String name, String selectorValue, String description) { }
    public record ScenarioInput(String browserProfileId, String name, String description, String failureStrategy,
                                Map<String, String> variables, List<UiAutomationStep> steps) { }
    public record OutboxMessage(String id, String executionId, String messageType, String streamKey, Map<String, String> payload,
                                int dispatchAttempts, int maxDispatchAttempts) { }
    public record StoredArtifact(UiAutomationExecutionArtifact artifact, String projectId, String objectKey) { }
}
