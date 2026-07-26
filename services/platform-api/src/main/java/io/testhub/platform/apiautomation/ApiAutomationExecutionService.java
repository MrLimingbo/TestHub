package io.testhub.platform.apiautomation;

import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ProjectSummary;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.parameter.ProjectParameterRepository;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ApiAutomationExecutionService {
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private static final Map<String, String> EVENT_STATUS = Map.of(
            "ACCEPTED", "ACCEPTED", "RUNNING", "RUNNING", "SUCCEEDED", "SUCCEEDED", "FAILED", "FAILED",
            "CANCELLED", "CANCELLED", "REJECTED", "REJECTED");
    private static final Pattern VARIABLE_REFERENCE = Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_.-]{0,127})\\s*}}");
    private static final String RUNNER_LOCAL_SOURCE = "RUNNER_LOCAL";
    private final ApiAutomationExecutionRepository executions;
    private final ApiAutomationScenarioRepository scenarios;
    private final ApiAutomationScenarioExpansionService expansions;
    private final ApiAutomationExecutionProfileService executionProfiles;
    private final AccessService access;
    private final AuditService audit;
    private final ProjectParameterRepository parameters;

    public ApiAutomationExecutionService(ApiAutomationExecutionRepository executions, ApiAutomationScenarioRepository scenarios,
                                         ApiAutomationScenarioExpansionService expansions, AccessService access,
                                         AuditService audit, ProjectParameterRepository parameters,
                                         ApiAutomationExecutionProfileService executionProfiles) {
        this.executions = executions;
        this.scenarios = scenarios;
        this.expansions = expansions;
        this.access = access;
        this.audit = audit;
        this.parameters = parameters;
        this.executionProfiles = executionProfiles;
    }

    public List<ApiAutomationExecution> list(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectRead(principal, projectId);
        return executions.findByProject(projectId);
    }

    public ApiAutomationExecution detail(AuthenticatedPrincipal principal, String projectId, String executionId) {
        access.requireProjectRead(principal, projectId);
        return executions.findById(projectId, executionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "接口自动化执行任务不存在"));
    }

    public List<ApiAutomationExecutionLog> logs(AuthenticatedPrincipal principal, String projectId, String executionId) {
        detail(principal, projectId, executionId);
        return executions.findLogs(projectId, executionId);
    }

    public List<ApiAutomationExecutionHttpResult> httpResults(AuthenticatedPrincipal principal, String projectId,
                                                               String executionId) {
        detail(principal, projectId, executionId);
        return executions.findHttpResults(projectId, executionId);
    }

    public List<ApiAutomationExecutionStepResult> stepResults(AuthenticatedPrincipal principal, String projectId,
                                                               String executionId) {
        detail(principal, projectId, executionId);
        return executions.findStepResults(projectId, executionId);
    }

    public List<ApiAutomationExecutionScriptResult> scriptResults(AuthenticatedPrincipal principal, String projectId,
                                                                   String executionId) {
        detail(principal, projectId, executionId);
        return executions.findScriptResults(projectId, executionId);
    }

    public ApiAutomationExecutionManifest manifest(AuthenticatedPrincipal principal, String projectId, String executionId) {
        detail(principal, projectId, executionId);
        return executions.findManifest(projectId, executionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "接口自动化执行清单不存在"));
    }

    public ApiAutomationExecutionRuntimeSnapshotSummary runtimeSnapshot(AuthenticatedPrincipal principal, String projectId,
                                                                         String executionId) {
        detail(principal, projectId, executionId);
        ApiAutomationExecutionRuntimeSnapshot snapshot = executions.findRuntimeSnapshot(projectId, executionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "接口自动化运行时变量快照不存在"));
        List<ApiAutomationExecutionRuntimeSnapshotSummary.Variable> variables = snapshot.snapshot().variables().stream()
                .map(variable -> new ApiAutomationExecutionRuntimeSnapshotSummary.Variable(variable.name(), variable.source()))
                .toList();
        return new ApiAutomationExecutionRuntimeSnapshotSummary(snapshot.id(), snapshot.executionId(), snapshot.checksum(),
                snapshot.variableCount(), snapshot.withheldSecretCount(),
                new ApiAutomationExecutionRuntimeSnapshotSummary.Snapshot(snapshot.snapshot().contract(),
                        snapshot.snapshot().snapshotVersion(), snapshot.snapshot().environmentCode(), variables,
                        snapshot.snapshot().withheldSecretNames()), snapshot.createdAt());
    }

    public ApiAutomationExecutionReport report(AuthenticatedPrincipal principal, String projectId, String executionId) {
        ApiAutomationExecution execution = detail(principal, projectId, executionId);
        ApiAutomationExecutionManifest manifest = executions.findManifest(projectId, executionId).orElse(null);
        ApiAutomationExecutionRuntimeSnapshot runtime = executions.findRuntimeSnapshot(projectId, executionId).orElse(null);
        ApiAutomationExecutionRepository.ExecutionResultTotals totals = executions.resultTotals(projectId, executionId);
        List<ApiAutomationExecutionHttpResult> httpResults = executions.findHttpResults(projectId, executionId);
        ApiAutomationExecutionReport.ManifestSummary manifestSummary = manifest == null ? null
                : new ApiAutomationExecutionReport.ManifestSummary(manifest.checksum(), manifest.stepCount(),
                manifest.variableCount(), manifest.secretReferenceCount(), manifest.snapshot().environmentCode(),
                manifest.snapshot().failureStrategy());
        ApiAutomationExecutionReport.RuntimeSummary runtimeSummary = runtime == null ? null
                : new ApiAutomationExecutionReport.RuntimeSummary(runtime.checksum(), runtime.variableCount(),
                runtime.withheldSecretCount(), runtime.snapshot().environmentCode());
        ApiAutomationExecutionReport.FailureStrategySummary failureSummary = manifestSummary == null ? null
                : new ApiAutomationExecutionReport.FailureStrategySummary(manifestSummary.failureStrategy(),
                executions.countRunnerLogsByCode(projectId, executionId, "FAILURE_CONTINUED"));
        return new ApiAutomationExecutionReport("testhub.api-automation.execution-report", 5, execution,
                manifestSummary, runtimeSummary, failureSummary, new ApiAutomationExecutionReport.ResultSummary(
                totals.httpResultCount(), totals.httpSucceededCount(), totals.httpFailedCount(), totals.httpDurationMillis(),
                totals.stepResultCount(), totals.assertionPassedCount(), totals.assertionFailedCount(), totals.scriptResultCount(),
                totals.scriptTestCount(), totals.scriptPassedCount(), totals.scriptFailedCount()),
                parallelGroups(manifest, httpResults), httpResults, executions.findStepResults(projectId, executionId),
                executions.findScriptResults(projectId, executionId));
    }

    private List<ApiAutomationExecutionReport.ParallelGroupSummary> parallelGroups(ApiAutomationExecutionManifest manifest,
                                                                                     List<ApiAutomationExecutionHttpResult> httpResults) {
        if (manifest == null) return List.of();
        Map<String, List<ApiAutomationExecutionHttpResult>> resultsByStep = new LinkedHashMap<>();
        for (ApiAutomationExecutionHttpResult result : httpResults) {
            resultsByStep.computeIfAbsent(result.stepId(), ignored -> new ArrayList<>()).add(result);
        }
        return manifest.snapshot().steps().stream().filter(step -> "PARALLEL".equals(step.type())).map(step -> {
            List<ApiAutomationExecutionHttpResult> results = resultsByStep.getOrDefault(step.id(), List.of());
            TreeMap<Integer, ApiAutomationExecutionHttpResult> finalAttemptBySequence = new TreeMap<>();
            for (ApiAutomationExecutionHttpResult result : results) {
                ApiAutomationExecutionHttpResult current = finalAttemptBySequence.get(result.requestSequence());
                if (current == null || result.attemptNumber() > current.attemptNumber()) {
                    finalAttemptBySequence.put(result.requestSequence(), result);
                }
            }
            int succeeded = (int) finalAttemptBySequence.values().stream()
                    .filter(result -> "SUCCEEDED".equals(result.outcome())).count();
            int completed = finalAttemptBySequence.size();
            Integer firstSequence = finalAttemptBySequence.isEmpty() ? null : finalAttemptBySequence.firstKey();
            Integer lastSequence = finalAttemptBySequence.isEmpty() ? null : finalAttemptBySequence.lastKey();
            long totalDuration = results.stream().mapToLong(ApiAutomationExecutionHttpResult::durationMillis).sum();
            return new ApiAutomationExecutionReport.ParallelGroupSummary(step.id(), step.parallelRequestCount(), completed,
                    succeeded, completed - succeeded, results.size(), results.size() - completed,
                    firstSequence, lastSequence, totalDuration);
        }).toList();
    }

    @Transactional
    public ApiAutomationExecution create(AuthenticatedPrincipal principal, String projectId, String scenarioId, Integer timeoutSeconds,
                                         String executionProfileId, Map<String, String> overrides) {
        ProjectSummary project = access.requireApiAutomationExecution(principal, projectId, WRITERS);
        ApiAutomationScenarioDetail scenario = scenarios.findDetail(projectId, scenarioId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "接口自动化场景不存在"));
        ApiAutomationExecutionProfileDetail profile = executionProfileId == null || executionProfileId.isBlank() ? null
                : executionProfiles.requireForExecution(projectId, executionProfileId);
        return queueExecution(principal, project, scenario, timeout(timeoutSeconds), null, null, null, profile,
                executionOverrides(overrides), Instant.now());
    }

    /**
     * Resolves the exact source of each referenced variable without creating an execution, an audit event, an
     * outbox record, or a value-bearing snapshot. The same resolver is used by the subsequent submit operation.
     */
    @Transactional(readOnly = true)
    public ApiAutomationExecutionConfigurationPreview previewConfiguration(AuthenticatedPrincipal principal, String projectId,
                                                                            String scenarioId, String executionProfileId,
                                                                            Map<String, String> overrides) {
        access.requireApiAutomationExecution(principal, projectId, WRITERS);
        ApiAutomationScenarioDetail scenario = scenarios.findDetail(projectId, scenarioId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "接口自动化场景不存在"));
        ApiAutomationExecutionProfileDetail profile = executionProfileId == null || executionProfileId.isBlank() ? null
                : executionProfiles.requireForExecution(projectId, executionProfileId);
        ApiAutomationScenarioDetail expandedScenario = expandedScenario(projectId, scenario, profile);
        VariableResolution variables = resolveVariables(projectId, expandedScenario, null,
                profile == null ? Map.of() : profile.variables(), executionOverrides(overrides));
        List<ApiAutomationExecutionConfigurationPreview.VariableReference> references = variables.locations().entrySet().stream()
                .map(entry -> {
                    VariableSource source = variables.selectedSources().get(entry.getKey());
                    return new ApiAutomationExecutionConfigurationPreview.VariableReference(entry.getKey(), source.source(),
                            source.secret(), entry.getValue());
                }).toList();
        return new ApiAutomationExecutionConfigurationPreview(scenario.id(), scenario.version(), expandedScenario.environmentCode(),
                profile == null ? null : profile.id(), profile == null ? null : profile.version(), references);
    }

    /**
     * A data set never crosses the Runner message boundary. Each row is resolved and frozen as an independent
     * execution, so cancellation, retry, expiry, and audit behavior remain identical to a normal run.
     */
    @Transactional
    public ApiAutomationDataSetExecutionBatch createDataSetExecutions(AuthenticatedPrincipal principal, String projectId,
                                                                       String scenarioId, ApiAutomationDataSetContent dataSet,
                                                                       Integer timeoutSeconds) {
        ProjectSummary project = access.requireApiAutomationExecution(principal, projectId, WRITERS);
        if (dataSet == null || !projectId.equals(dataSet.dataSet().projectId()) || dataSet.rows().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "接口自动化数据集不存在");
        }
        ApiAutomationScenarioDetail scenario = scenarios.findDetail(projectId, scenarioId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "接口自动化场景不存在"));
        String environmentCode = scenario.environmentCode().trim().toLowerCase(Locale.ROOT);
        Set<String> environmentSecretNames = parameters.findSecretsByProject(projectId, environmentCode).stream()
                .map(secret -> secret.key()).collect(java.util.stream.Collectors.toSet());
        List<String> secretCollisions = dataSet.dataSet().columns().stream().filter(environmentSecretNames::contains).toList();
        if (!secretCollisions.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "数据集字段不能覆盖当前环境密钥：" + String.join("、", secretCollisions));
        }
        int timeout = timeout(timeoutSeconds);
        Instant requestedAt = Instant.now();
        List<ApiAutomationExecution> created = new ArrayList<>();
        for (int index = 0; index < dataSet.rows().size(); index++) {
            created.add(queueExecution(principal, project, scenario, timeout, dataSet.rows().get(index),
                    dataSet.dataSet().id(), index + 1, null, Map.of(), requestedAt));
        }
        return new ApiAutomationDataSetExecutionBatch(dataSet.dataSet().id(), dataSet.dataSet().name(), created.size(), created);
    }

    private ApiAutomationExecution queueExecution(AuthenticatedPrincipal principal, ProjectSummary project,
                                                   ApiAutomationScenarioDetail scenario, int timeout,
                                                   Map<String, String> dataSetRow, String dataSetId,
                                                   Integer dataSetRowNumber, ApiAutomationExecutionProfileDetail profile,
                                                   Map<String, String> overrides, Instant requestedAt) {
        String projectId = project.id();
        ApiAutomationScenarioDetail expandedScenario = expandedScenario(projectId, scenario, profile);
        VariableResolution variables = resolveVariables(projectId, expandedScenario, dataSetRow,
                profile == null ? Map.of() : profile.variables(), overrides);
        String executionId = UlidCreator.getUlid().toString();
        String taskId = UlidCreator.getUlid().toString();
        Instant deadlineAt = requestedAt.plus(Duration.ofSeconds(timeout));
        String manifestRef = "api-automation/manifests/" + scenario.id() + "/v" + scenario.version() + ".json";
        executions.insertExecution(executionId, taskId, project.organizationId(), projectId, scenario.id(), scenario.name(),
                scenario.version(), profile == null ? null : profile.id(), profile == null ? null : profile.version(), dataSetId,
                dataSetRowNumber, manifestRef, principal.userId(), requestedAt, deadlineAt);
        ApiAutomationExecution queued = executions.findById(projectId, executionId).orElseThrow();
        ApiAutomationExecutionManifest.Snapshot manifest = manifestSnapshot(expandedScenario, variables);
        String manifestContent = executions.json(manifest);
        String checksum = sha256(manifestContent);
        executions.insertManifest(UlidCreator.getUlid().toString(), queued, manifest, manifestContent, checksum);
        ApiAutomationExecutionRuntimeSnapshot.Snapshot runtimeSnapshot = runtimeSnapshot(expandedScenario, variables);
        String runtimeSnapshotContent = executions.json(runtimeSnapshot);
        String runtimeSnapshotChecksum = sha256(runtimeSnapshotContent);
        executions.insertRuntimeSnapshot(UlidCreator.getUlid().toString(), queued, runtimeSnapshot,
                runtimeSnapshotContent, runtimeSnapshotChecksum);
        executions.appendControlPlaneLog(queued, "MANIFEST_FROZEN", "已固定场景结构与变量引用元数据；未保存变量或密钥值。", requestedAt);
        if (expandedScenario.steps().size() != scenario.steps().size()) {
            executions.appendControlPlaneLog(queued, "SCENARIO_REFERENCES_EXPANDED",
                    "已根据固定来源版本展开公共步骤和子场景；Runner 只会收到基础步骤。", requestedAt);
        }
        if (profile != null) {
            executions.appendControlPlaneLog(queued, "EXECUTION_PROFILE_FROZEN",
                    "已固定执行配置集 v" + profile.version() + " 的环境与非敏感变量；取值不展示。", requestedAt);
        }
        if (dataSetId != null) {
            executions.appendControlPlaneLog(queued, "DATA_SET_ROW_FROZEN",
                    "已固定数据集第 " + dataSetRowNumber + " 行的非敏感变量；取值不展示。", requestedAt);
        }
        executions.insertOutbox(taskId, executionId, "TASK", ApiRunnerProtocol.TASK_STREAM,
                taskPayload(taskId, executionId, project.organizationId(), projectId, scenario, requestedAt, deadlineAt, manifestRef));
        executions.appendControlPlaneLog(queued, "QUEUED", "任务已写入安全 Outbox，等待 Runner 投递。", requestedAt);
        audit.record(principal, project.organizationId(), projectId, "API_AUTOMATION_EXECUTION_MANIFEST_FROZEN",
                "API_AUTOMATION_EXECUTION_MANIFEST", executionId, Map.of("scenarioId", scenario.id(),
                        "scenarioVersion", scenario.version(), "checksum", checksum, "stepCount", manifest.steps().size(),
                        "variableCount", manifest.variableBindings().size(), "secretReferenceCount",
                        manifest.variableBindings().stream().filter(ApiAutomationExecutionManifest.VariableBinding::secret).count()));
        audit.record(principal, project.organizationId(), projectId, "API_AUTOMATION_EXECUTION_RUNTIME_SNAPSHOT_FROZEN",
                "API_AUTOMATION_EXECUTION_RUNTIME_SNAPSHOT", executionId, Map.of("scenarioId", scenario.id(),
                        "scenarioVersion", scenario.version(), "checksum", runtimeSnapshotChecksum,
                        "variableCount", runtimeSnapshot.variables().size(),
                        "withheldSecretCount", runtimeSnapshot.withheldSecretNames().size()));
        audit.record(principal, project.organizationId(), projectId, "API_AUTOMATION_EXECUTION_QUEUED",
                "API_AUTOMATION_EXECUTION", executionId, Map.of("taskId", taskId, "scenarioId", scenario.id(),
                        "scenarioVersion", scenario.version(), "executionProfileId", profile == null ? "" : profile.id(),
                        "executionProfileVersion", profile == null ? 0 : profile.version(), "timeoutSeconds", timeout));
        return queued;
    }

    private ApiAutomationScenarioDetail expandedScenario(String projectId, ApiAutomationScenarioDetail source,
                                                         ApiAutomationExecutionProfileDetail profile) {
        List<AutomationStep> steps = expansions.expand(projectId, source.id(), source.steps());
        return new ApiAutomationScenarioDetail(source.id(), source.organizationId(), source.projectId(), source.name(),
                source.description(), profile == null ? source.environmentCode() : profile.environmentCode(), source.failureStrategy(), source.version(), steps,
                source.variables(), source.preRequestScript(), source.postResponseScript(), source.createdBy(),
                source.createdAt(), source.updatedAt());
    }

    @Transactional
    public ApiAutomationExecution cancel(AuthenticatedPrincipal principal, String projectId, String executionId) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
        ApiAutomationExecution current = detail(principal, projectId, executionId);
        if (isTerminal(current.status()) || "CANCELLING".equals(current.status())) return current;
        if (executions.cancelBeforeDispatch(projectId, executionId) > 0) {
            ApiAutomationExecution cancelled = executions.findById(projectId, executionId).orElseThrow();
            executions.appendControlPlaneLog(cancelled, "CANCELLED_BEFORE_DISPATCH", "任务在投递给 Runner 前已取消。", Instant.now());
            audit.record(principal, project.organizationId(), projectId, "API_AUTOMATION_EXECUTION_CANCELLED",
                    "API_AUTOMATION_EXECUTION", executionId, Map.of("taskId", cancelled.taskId(), "mode", "BEFORE_DISPATCH"));
            return cancelled;
        }
        if (executions.markCancelling(projectId, executionId) == 0) {
            return executions.findById(projectId, executionId).orElseThrow();
        }
        String commandId = UlidCreator.getUlid().toString();
        Instant requestedAt = Instant.now();
        executions.insertOutbox(commandId, executionId, "CANCEL", ApiRunnerProtocol.CANCEL_STREAM,
                cancelPayload(commandId, current, requestedAt, "USER_REQUESTED"));
        ApiAutomationExecution cancelling = executions.findById(projectId, executionId).orElseThrow();
        executions.appendControlPlaneLog(cancelling, "CANCEL_REQUESTED", "已通过安全 Outbox 请求 Runner 取消任务。", requestedAt);
        audit.record(principal, project.organizationId(), projectId, "API_AUTOMATION_EXECUTION_CANCEL_REQUESTED",
                "API_AUTOMATION_EXECUTION", executionId, Map.of("taskId", current.taskId(), "commandId", commandId));
        return cancelling;
    }

    @Transactional
    public void expireDueExecutions() {
        for (ApiAutomationExecution candidate : executions.findExpiredActive()) {
            if (executions.markTimedOut(candidate.id()) == 0) continue;
            executions.cancelPendingTaskOutbox(candidate.id());
            if (executions.hasTaskPossiblyDispatched(candidate.id()) && !executions.hasActiveCancellationCommand(candidate.id())) {
                String commandId = UlidCreator.getUlid().toString();
                executions.insertOutbox(commandId, candidate.id(), "CANCEL", ApiRunnerProtocol.CANCEL_STREAM,
                        cancelPayload(commandId, candidate, Instant.now(), "EXECUTION_TIMEOUT"));
            }
            executions.appendControlPlaneLog(candidate, "EXECUTION_TIMEOUT", "任务已达到截止时间，已停止等待 Runner 结果。", Instant.now());
            audit.recordSystem(candidate.organizationId(), candidate.projectId(), "API_AUTOMATION_EXECUTION_TIMED_OUT",
                    "API_AUTOMATION_EXECUTION", candidate.id(), Map.of("taskId", candidate.taskId()));
        }
    }

    @Transactional
    public void applyRunnerEvent(ApiRunnerProtocol.ApiRunnerEvent event) {
        String status = EVENT_STATUS.get(event.type());
        if (status == null) throw new IllegalArgumentException("Runner 事件类型不受支持");
        ApiAutomationExecution current = executions.findByTask(event.taskId(), event.executionId()).orElse(null);
        if (current == null) return;
        int updated = executions.applyRunnerEvent(event.taskId(), event.executionId(), status, event.code(), event.message(),
                event.runnerId(), event.occurredAt());
        if (updated > 0) {
            audit.recordSystem(current.organizationId(), current.projectId(), "API_AUTOMATION_EXECUTION_EVENT_RECEIVED",
                    "API_AUTOMATION_EXECUTION", current.id(), Map.of("taskId", current.taskId(), "runnerId", event.runnerId(),
                            "type", event.type(), "code", event.code()));
        }
    }

    @Transactional
    public void applyRunnerLog(ApiRunnerProtocol.ApiRunnerLog log, String streamRecordId) {
        ApiAutomationExecution current = executions.findByTask(log.taskId(), log.executionId()).orElse(null);
        if (current == null) return;
        executions.appendRunnerLog(current, log, streamRecordId);
    }

    @Transactional
    public void applyRunnerHttpResult(ApiRunnerProtocol.ApiRunnerHttpResult result, String streamRecordId) {
        ApiAutomationExecution current = executions.findByTask(result.taskId(), result.executionId()).orElse(null);
        if (current == null || !isKnownHttpStep(current, result)) return;
        executions.appendRunnerHttpResult(current, result, streamRecordId);
    }

    @Transactional
    public void applyRunnerStepResult(ApiRunnerProtocol.ApiRunnerStepResult result, String streamRecordId) {
        ApiAutomationExecution current = executions.findByTask(result.taskId(), result.executionId()).orElse(null);
        if (current == null || !isKnownStep(current, result)) return;
        executions.appendRunnerStepResult(current, result, streamRecordId);
    }

    @Transactional
    public void applyRunnerScriptResult(ApiRunnerProtocol.ApiRunnerScriptResult result, String streamRecordId) {
        ApiAutomationExecution current = executions.findByTask(result.taskId(), result.executionId()).orElse(null);
        if (current == null || !isConfiguredScript(current, result)) return;
        executions.appendRunnerScriptResult(current, result, streamRecordId);
    }

    @Transactional
    public void recordDeliveryFailure(ApiAutomationExecution execution) {
        String message = "CANCELLATION_DELIVERY_FAILED".equals(execution.statusCode())
                ? "取消指令已达到投递上限，无法确认 Runner 已停止任务。"
                : "任务已达到 Outbox 投递上限，已停止重试。";
        executions.appendControlPlaneLog(execution, execution.statusCode(), message, Instant.now());
    }

    private Map<String, String> taskPayload(String taskId, String executionId, String organizationId, String projectId,
                                            ApiAutomationScenarioDetail scenario, Instant requestedAt, Instant deadlineAt,
                                            String manifestRef) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("contract", ApiRunnerProtocol.TASK_CONTRACT);
        payload.put("protocolVersion", ApiRunnerProtocol.VERSION);
        payload.put("taskId", taskId);
        payload.put("executionId", executionId);
        payload.put("organizationId", organizationId);
        payload.put("projectId", projectId);
        payload.put("scenarioId", scenario.id());
        payload.put("scenarioVersion", String.valueOf(scenario.version()));
        payload.put("attempt", "1");
        payload.put("requestedAt", requestedAt.toString());
        payload.put("deadlineAt", deadlineAt.toString());
        payload.put("manifestRef", manifestRef);
        payload.put("traceId", "execution-" + executionId);
        return Map.copyOf(payload);
    }

    private Map<String, String> cancelPayload(String commandId, ApiAutomationExecution execution, Instant requestedAt,
                                              String reasonCode) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("contract", ApiRunnerProtocol.CANCEL_CONTRACT);
        payload.put("protocolVersion", ApiRunnerProtocol.VERSION);
        payload.put("commandId", commandId);
        payload.put("taskId", execution.taskId());
        payload.put("executionId", execution.id());
        payload.put("requestedAt", requestedAt.toString());
        payload.put("reasonCode", reasonCode);
        return Map.copyOf(payload);
    }

    private ApiAutomationExecutionManifest.Snapshot manifestSnapshot(ApiAutomationScenarioDetail scenario,
                                                                      VariableResolution variables) {
        List<ApiAutomationExecutionManifest.Step> steps = scenario.steps().stream()
                .map(step -> new ApiAutomationExecutionManifest.Step(step.id(), step.type(), step.name(),
                        "REQUEST".equals(step.type()) ? String.valueOf(step.config().get("method")).toUpperCase(Locale.ROOT) : null,
                        parallelRequestCount(step), parallelRequestMethods(step),
                        step.config().keySet().stream().sorted().toList()))
                .toList();
        List<ApiAutomationExecutionManifest.VariableBinding> bindings = variables.sources().stream()
                .map(source -> new ApiAutomationExecutionManifest.VariableBinding(source.name(), source.source(), source.secret()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        bindings.sort(Comparator.comparing(ApiAutomationExecutionManifest.VariableBinding::source)
                .thenComparing(ApiAutomationExecutionManifest.VariableBinding::name));
        List<ApiAutomationExecutionManifest.VariableReference> references = variables.locations().entrySet().stream()
                .map(entry -> {
                    VariableSource source = variables.selectedSources().get(entry.getKey());
                    return new ApiAutomationExecutionManifest.VariableReference(entry.getKey(), source.source(), source.secret(), entry.getValue());
                }).toList();
        ApiAutomationExecutionManifest.ScriptSummary scripts = new ApiAutomationExecutionManifest.ScriptSummary(
                !scenario.preRequestScript().isBlank(), scenario.preRequestScript().isBlank() ? null : sha256(scenario.preRequestScript()),
                !scenario.postResponseScript().isBlank(), scenario.postResponseScript().isBlank() ? null : sha256(scenario.postResponseScript()));
        return new ApiAutomationExecutionManifest.Snapshot("testhub.api-automation.execution-manifest", 5,
                scenario.environmentCode(), scenario.failureStrategy(), steps, bindings, references, scripts);
    }

    private ApiAutomationExecutionRuntimeSnapshot.Snapshot runtimeSnapshot(ApiAutomationScenarioDetail scenario,
                                                                            VariableResolution variables) {
        List<ApiAutomationExecutionRuntimeSnapshot.Variable> resolved = new ArrayList<>();
        List<String> withheldSecretNames = new ArrayList<>();
        for (VariableSource source : variables.selectedSources().values()) {
            if (RUNNER_LOCAL_SOURCE.equals(source.source())) continue;
            if (source.secret()) {
                withheldSecretNames.add(source.name());
            } else {
                resolved.add(new ApiAutomationExecutionRuntimeSnapshot.Variable(source.name(), source.source(), source.value()));
            }
        }
        return new ApiAutomationExecutionRuntimeSnapshot.Snapshot("testhub.api-automation.runtime-snapshot", 1,
                scenario.environmentCode(), resolved, withheldSecretNames);
    }

    private boolean isKnownHttpStep(ApiAutomationExecution execution, ApiRunnerProtocol.ApiRunnerHttpResult result) {
        return executions.findManifest(execution.projectId(), execution.id()).map(manifest -> manifest.snapshot().steps().stream()
                .anyMatch(step -> (result.stepId().equals(step.id()) && "REQUEST".equals(step.type())
                        && result.method().equals(step.requestMethod()))
                        || (result.stepId().equals(step.id()) && "PARALLEL".equals(step.type())
                        && step.parallelRequestMethods().contains(result.method())))).orElse(false);
    }

    private int parallelRequestCount(AutomationStep step) {
        Object value = step.config().get("requests");
        return "PARALLEL".equals(step.type()) && value instanceof List<?> requests ? requests.size() : 0;
    }

    private List<String> parallelRequestMethods(AutomationStep step) {
        Object value = step.config().get("requests");
        if (!"PARALLEL".equals(step.type()) || !(value instanceof List<?> requests)) return List.of();
        return requests.stream().map(request -> request instanceof Map<?, ?> config && config.get("method") instanceof String method
                ? method.toUpperCase(Locale.ROOT) : "").filter(method -> !method.isBlank()).toList();
    }

    private boolean isKnownStep(ApiAutomationExecution execution, ApiRunnerProtocol.ApiRunnerStepResult result) {
        return executions.findManifest(execution.projectId(), execution.id()).map(manifest -> manifest.snapshot().steps().stream()
                .anyMatch(step -> result.stepId().equals(step.id()) && result.type().equals(step.type()))).orElse(false);
    }

    private boolean isConfiguredScript(ApiAutomationExecution execution, ApiRunnerProtocol.ApiRunnerScriptResult result) {
        return executions.findManifest(execution.projectId(), execution.id()).map(manifest -> "PRE_REQUEST".equals(result.phase())
                ? manifest.snapshot().scripts().preRequestConfigured()
                : manifest.snapshot().scripts().postResponseConfigured()).orElse(false);
    }

    private int timeout(Integer timeoutSeconds) {
        int timeout = timeoutSeconds == null ? 300 : timeoutSeconds;
        if (timeout < 10 || timeout > 3600) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "执行超时时间应在 10 到 3600 秒之间");
        }
        return timeout;
    }

    private VariableResolution resolveVariables(String projectId, ApiAutomationScenarioDetail scenario,
                                                Map<String, String> dataSetRow, Map<String, String> profileVariables,
                                                Map<String, String> overrides) {
        String environmentCode = scenario.environmentCode().trim().toLowerCase(Locale.ROOT);
        Set<String> environmentSecretNames = parameters.findSecretsByProject(projectId, environmentCode).stream()
                .map(secret -> secret.key()).collect(java.util.stream.Collectors.toSet());
        Set<String> reservedNames = new java.util.TreeSet<>();
        profileVariables.keySet().stream().filter(environmentSecretNames::contains).forEach(reservedNames::add);
        overrides.keySet().stream().filter(environmentSecretNames::contains).forEach(reservedNames::add);
        if (!reservedNames.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "执行配置集或本次覆盖不能覆盖当前环境密钥：" + String.join("、", reservedNames));
        }
        List<VariableSource> sources = new ArrayList<>();
        scenario.variables().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                sources.add(new VariableSource(entry.getKey(), "SCENARIO", false, 2, entry.getValue())));
        parameters.findByProject(projectId, environmentCode).forEach(parameter ->
                sources.add(new VariableSource(parameter.key(), "PROJECT_PARAMETER", false, 1, parameter.value())));
        if (dataSetRow != null) {
            dataSetRow.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                    sources.add(new VariableSource(entry.getKey(), "DATA_SET", false, 4, entry.getValue())));
        }
        profileVariables.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                sources.add(new VariableSource(entry.getKey(), "EXECUTION_PROFILE", false, 3, entry.getValue())));
        overrides.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                sources.add(new VariableSource(entry.getKey(), "EXECUTION_OVERRIDE", false, 5, entry.getValue())));
        parameters.findSecretsByProject(projectId, environmentCode).forEach(secret ->
                sources.add(new VariableSource(secret.key(), "ENVIRONMENT_SECRET", true, 6, null)));
        Map<String, VariableSource> selectedSources = new TreeMap<>();
        for (VariableSource source : sources) {
            selectedSources.merge(source.name(), source,
                    (current, candidate) -> candidate.priority() > current.priority() ? candidate : current);
        }
        Map<String, List<ApiAutomationExecutionManifest.ReferenceLocation>> locations = variableLocations(scenario);
        Set<String> runnerLocalVariables = runnerLocalVariables(scenario);
        validateRunnerLocalReferences(scenario, runnerLocalVariables);
        for (String name : runnerLocalVariables) {
            if (selectedSources.containsKey(name)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "数据提取变量不能覆盖已定义变量：" + name);
            }
            VariableSource source = new VariableSource(name, RUNNER_LOCAL_SOURCE, false, 7, null);
            sources.add(source);
            selectedSources.put(name, source);
        }
        List<String> unresolved = locations.keySet().stream().filter(name -> !selectedSources.containsKey(name)).toList();
        if (!unresolved.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "场景引用了未定义变量：" + String.join("、", unresolved));
        }
        return new VariableResolution(List.copyOf(sources), Map.copyOf(selectedSources), Map.copyOf(locations));
    }

    private Map<String, String> executionOverrides(Map<String, String> overrides) {
        return ApiAutomationExecutionProfileService.validateVariables(overrides, "本次执行覆盖");
    }

    private Set<String> runnerLocalVariables(ApiAutomationScenarioDetail scenario) {
        Set<String> variables = new java.util.LinkedHashSet<>();
        for (AutomationStep step : scenario.steps()) {
            if (!"EXTRACT".equals(step.type())) continue;
            Object configured = step.config().get("variable");
            if (!(configured instanceof String variable) || !variable.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "数据提取变量配置不合法");
            }
            if (!variables.add(variable)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "数据提取变量名重复：" + variable);
            }
        }
        return Set.copyOf(variables);
    }

    private void validateRunnerLocalReferences(ApiAutomationScenarioDetail scenario, Set<String> runnerLocalVariables) {
        Set<String> available = new java.util.LinkedHashSet<>();
        for (AutomationStep step : scenario.steps()) {
            for (String text : stringConfigValues(step.config().values())) {
                Matcher matcher = VARIABLE_REFERENCE.matcher(text);
                while (matcher.find()) {
                    String name = matcher.group(1);
                    if ("PARALLEL".equals(step.type()) && runnerLocalVariables.contains(name)) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "并行请求组不能引用数据提取局部变量：" + name);
                    }
                    if (runnerLocalVariables.contains(name) && !available.contains(name)) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "局部变量只能在数据提取之后引用：" + name);
                    }
                }
            }
            if ("EXTRACT".equals(step.type())) available.add(String.valueOf(step.config().get("variable")));
        }
    }

    private Map<String, List<ApiAutomationExecutionManifest.ReferenceLocation>> variableLocations(ApiAutomationScenarioDetail scenario) {
        Map<String, List<ApiAutomationExecutionManifest.ReferenceLocation>> locations = new TreeMap<>();
        for (AutomationStep step : scenario.steps()) {
            step.config().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                for (String value : stringConfigValues(List.of(entry.getValue()))) {
                    Matcher matcher = VARIABLE_REFERENCE.matcher(value);
                    while (matcher.find()) {
                        locations.computeIfAbsent(matcher.group(1), ignored -> new ArrayList<>())
                                .add(new ApiAutomationExecutionManifest.ReferenceLocation(step.id(), entry.getKey()));
                    }
                }
            });
        }
        return locations;
    }

    private List<String> stringConfigValues(java.util.Collection<?> source) {
        List<String> result = new ArrayList<>();
        for (Object value : source) collectStringConfigValues(value, result);
        return List.copyOf(result);
    }

    private void collectStringConfigValues(Object value, List<String> target) {
        if (value instanceof String text) {
            target.add(text);
        } else if (value instanceof Map<?, ?> map) {
            map.values().forEach(item -> collectStringConfigValues(item, target));
        } else if (value instanceof List<?> list) {
            list.forEach(item -> collectStringConfigValues(item, target));
        }
    }

    private String sha256(String source) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", exception);
        }
    }

    private boolean isTerminal(String status) {
        return Set.of("SUCCEEDED", "FAILED", "CANCELLED", "REJECTED", "TIMED_OUT").contains(status);
    }

    private record VariableSource(String name, String source, boolean secret, int priority, String value) {
    }

    private record VariableResolution(List<VariableSource> sources, Map<String, VariableSource> selectedSources,
                                      Map<String, List<ApiAutomationExecutionManifest.ReferenceLocation>> locations) {
    }
}
