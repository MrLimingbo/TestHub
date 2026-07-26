package io.testhub.platform.uiautomation;

import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ProjectSummary;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.functional.FunctionalTestExecutionRepository;
import io.testhub.platform.functional.FunctionalTestPlan;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UiAutomationService {
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private static final Set<String> BROWSERS = Set.of("chromium", "firefox", "webkit");
    private static final Set<String> FAILURE_STRATEGIES = Set.of("STOP", "CONTINUE");
    private static final Set<String> STEP_TYPES = Set.of("NAVIGATE", "FILL", "CLICK", "ASSERT_VISIBLE", "ASSERT_TEXT", "WAIT", "SCREENSHOT");
    private final UiAutomationRepository automation;
    private final UiAutomationScheduleRepository schedules;
    private final FunctionalTestExecutionRepository functionalPlans;
    private final AccessService access;
    private final AuditService audit;
    private final UiAutomationTargetPolicy targets;

    public UiAutomationService(UiAutomationRepository automation, UiAutomationScheduleRepository schedules,
                               FunctionalTestExecutionRepository functionalPlans, AccessService access, AuditService audit,
                               UiAutomationTargetPolicy targets) {
        this.automation = automation;
        this.schedules = schedules;
        this.functionalPlans = functionalPlans;
        this.access = access;
        this.audit = audit;
        this.targets = targets;
    }

    public List<UiAutomationBrowserProfile> profiles(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectRead(principal, projectId);
        return automation.findProfiles(projectId);
    }
    public List<UiAutomationPageObject> pageObjects(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectRead(principal, projectId);
        return automation.findPageObjects(projectId);
    }
    public List<UiAutomationScenario> scenarios(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectRead(principal, projectId);
        return automation.findScenarios(projectId);
    }
    public List<UiAutomationExecution> executions(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectRead(principal, projectId);
        return automation.findExecutions(projectId);
    }

    @Transactional
    public UiAutomationBrowserProfile createProfile(AuthenticatedPrincipal principal, String projectId, ProfileRequest request) {
        ProjectSummary project = writable(principal, projectId);
        UiAutomationRepository.ProfileInput input = profileInput(request);
        String id = UlidCreator.getUlid().toString();
        try { automation.insertProfile(id, project.organizationId(), projectId, input, principal.userId()); }
        catch (DataIntegrityViolationException exception) { throw conflict("当前项目已存在同名浏览器配置"); }
        UiAutomationBrowserProfile profile = automation.findProfile(projectId, id).orElseThrow();
        audit.record(principal, project.organizationId(), projectId, "UI_AUTOMATION_BROWSER_PROFILE_CREATED",
                "UI_AUTOMATION_BROWSER_PROFILE", id, Map.of("name", profile.name(), "browser", profile.browser()));
        return profile;
    }

    @Transactional
    public UiAutomationBrowserProfile updateProfile(AuthenticatedPrincipal principal, String projectId, String profileId,
                                                     ProfileRequest request) {
        ProjectSummary project = writable(principal, projectId);
        UiAutomationRepository.ProfileInput input = profileInput(request);
        try {
            if (automation.updateProfile(projectId, profileId, input, principal.userId()) == 0) throw notFound("浏览器配置不存在");
        } catch (DataIntegrityViolationException exception) { throw conflict("当前项目已存在同名浏览器配置"); }
        UiAutomationBrowserProfile profile = automation.findProfile(projectId, profileId).orElseThrow();
        audit.record(principal, project.organizationId(), projectId, "UI_AUTOMATION_BROWSER_PROFILE_UPDATED",
                "UI_AUTOMATION_BROWSER_PROFILE", profileId, Map.of("name", profile.name(), "version", profile.version()));
        return profile;
    }

    @Transactional
    public void deleteProfile(AuthenticatedPrincipal principal, String projectId, String profileId) {
        ProjectSummary project = writable(principal, projectId);
        UiAutomationBrowserProfile profile = profile(projectId, profileId);
        try { automation.deleteProfile(projectId, profileId); }
        catch (DataIntegrityViolationException exception) { throw conflict("浏览器配置正被场景或执行记录引用，不能删除"); }
        audit.record(principal, project.organizationId(), projectId, "UI_AUTOMATION_BROWSER_PROFILE_DELETED",
                "UI_AUTOMATION_BROWSER_PROFILE", profileId, Map.of("name", profile.name()));
    }

    @Transactional
    public UiAutomationPageObject createPageObject(AuthenticatedPrincipal principal, String projectId, PageObjectRequest request) {
        ProjectSummary project = writable(principal, projectId);
        UiAutomationRepository.PageObjectInput input = pageObjectInput(request);
        String id = UlidCreator.getUlid().toString();
        try { automation.insertPageObject(id, project.organizationId(), projectId, input, principal.userId()); }
        catch (DataIntegrityViolationException exception) { throw conflict("当前项目已存在同名页面对象"); }
        UiAutomationPageObject value = automation.findPageObject(projectId, id).orElseThrow();
        audit.record(principal, project.organizationId(), projectId, "UI_AUTOMATION_PAGE_OBJECT_CREATED",
                "UI_AUTOMATION_PAGE_OBJECT", id, Map.of("name", value.name()));
        return value;
    }

    @Transactional
    public UiAutomationPageObject updatePageObject(AuthenticatedPrincipal principal, String projectId, String pageObjectId,
                                                    PageObjectRequest request) {
        ProjectSummary project = writable(principal, projectId);
        UiAutomationRepository.PageObjectInput input = pageObjectInput(request);
        try {
            if (automation.updatePageObject(projectId, pageObjectId, input, principal.userId()) == 0) throw notFound("页面对象不存在");
        } catch (DataIntegrityViolationException exception) { throw conflict("当前项目已存在同名页面对象"); }
        UiAutomationPageObject value = automation.findPageObject(projectId, pageObjectId).orElseThrow();
        audit.record(principal, project.organizationId(), projectId, "UI_AUTOMATION_PAGE_OBJECT_UPDATED",
                "UI_AUTOMATION_PAGE_OBJECT", pageObjectId, Map.of("name", value.name()));
        return value;
    }

    @Transactional
    public void deletePageObject(AuthenticatedPrincipal principal, String projectId, String pageObjectId) {
        ProjectSummary project = writable(principal, projectId);
        UiAutomationPageObject value = pageObject(projectId, pageObjectId);
        if (automation.isPageObjectReferenced(projectId, pageObjectId)) {
            throw conflict("页面对象正被 Web UI 场景引用，不能删除");
        }
        automation.deletePageObject(projectId, pageObjectId);
        audit.record(principal, project.organizationId(), projectId, "UI_AUTOMATION_PAGE_OBJECT_DELETED",
                "UI_AUTOMATION_PAGE_OBJECT", pageObjectId, Map.of("name", value.name()));
    }

    @Transactional
    public UiAutomationScenario createScenario(AuthenticatedPrincipal principal, String projectId, ScenarioRequest request) {
        ProjectSummary project = writable(principal, projectId);
        UiAutomationRepository.ScenarioInput input = scenarioInput(projectId, request);
        String id = UlidCreator.getUlid().toString();
        try { automation.insertScenario(id, project.organizationId(), projectId, input, principal.userId()); }
        catch (DataIntegrityViolationException exception) { throw conflict("当前项目已存在同名 Web UI 场景"); }
        UiAutomationScenario scenario = automation.findScenario(projectId, id).orElseThrow();
        audit.record(principal, project.organizationId(), projectId, "UI_AUTOMATION_SCENARIO_CREATED", "UI_AUTOMATION_SCENARIO", id,
                Map.of("name", scenario.name(), "stepCount", scenario.steps().size()));
        return scenario;
    }

    @Transactional
    public UiAutomationScenario updateScenario(AuthenticatedPrincipal principal, String projectId, String scenarioId,
                                               ScenarioRequest request) {
        ProjectSummary project = writable(principal, projectId);
        UiAutomationRepository.ScenarioInput input = scenarioInput(projectId, request);
        try {
            if (automation.updateScenario(projectId, scenarioId, input, principal.userId()) == 0) throw notFound("Web UI 场景不存在");
        } catch (DataIntegrityViolationException exception) { throw conflict("当前项目已存在同名 Web UI 场景"); }
        UiAutomationScenario scenario = automation.findScenario(projectId, scenarioId).orElseThrow();
        audit.record(principal, project.organizationId(), projectId, "UI_AUTOMATION_SCENARIO_UPDATED", "UI_AUTOMATION_SCENARIO", scenarioId,
                Map.of("name", scenario.name(), "version", scenario.version(), "stepCount", scenario.steps().size()));
        return scenario;
    }

    @Transactional
    public void deleteScenario(AuthenticatedPrincipal principal, String projectId, String scenarioId) {
        ProjectSummary project = writable(principal, projectId);
        UiAutomationScenario scenario = scenario(projectId, scenarioId);
        try { automation.deleteScenario(projectId, scenarioId); }
        catch (DataIntegrityViolationException exception) { throw conflict("场景已有执行记录，不能删除"); }
        audit.record(principal, project.organizationId(), projectId, "UI_AUTOMATION_SCENARIO_DELETED", "UI_AUTOMATION_SCENARIO", scenarioId,
                Map.of("name", scenario.name()));
    }

    @Transactional
    public UiAutomationExecution execute(AuthenticatedPrincipal principal, String projectId, String scenarioId, Integer timeoutSeconds,
                                         String functionalPlanId) {
        ProjectSummary project = writable(principal, projectId);
        int timeout = timeout(timeoutSeconds);
        FunctionalTestPlan plan = functionalPlan(projectId, functionalPlanId);
        UiAutomationExecution execution = queueExecution(project.organizationId(), projectId, principal.userId(), scenarioId, timeout,
                null, plan, null, 0, Instant.now());
        String checksum = automation.findManifest(projectId, execution.id()).orElseThrow().checksum();
        audit.record(principal, project.organizationId(), projectId, "UI_AUTOMATION_EXECUTION_QUEUED", "UI_AUTOMATION_EXECUTION",
                execution.id(), Map.of("taskId", execution.taskId(), "scenarioId", execution.scenarioId(), "scenarioVersion", execution.scenarioVersion(),
                        "browser", execution.browser(), "timeoutSeconds", timeout, "functionalPlanId", plan == null ? "" : plan.id(), "checksum", checksum));
        return execution;
    }

    @Transactional
    public UiAutomationExecution executeFromSchedule(UiAutomationSchedule schedule) {
        if (!schedule.enabled()) throw conflict("已停用的定时计划不能执行");
        FunctionalTestPlan plan = functionalPlan(schedule.projectId(), schedule.functionalPlanId());
        Instant requestedAt = Instant.now();
        UiAutomationExecution execution = queueExecution(schedule.organizationId(), schedule.projectId(), schedule.createdBy(),
                schedule.scenarioId(), schedule.timeoutSeconds(), schedule, plan, null, 0, requestedAt);
        schedules.markTriggered(schedule.id(), execution.id(), requestedAt);
        return execution;
    }

    @Transactional
    public UiAutomationExecution retry(AuthenticatedPrincipal principal, String projectId, String executionId) {
        ProjectSummary project = writable(principal, projectId);
        UiAutomationExecution previous = execution(projectId, executionId);
        if (!Set.of("FAILED", "REJECTED", "TIMED_OUT").contains(previous.status())) {
            throw conflict("仅失败、拒绝或超时的执行记录可以重试");
        }
        FunctionalTestPlan plan = functionalPlan(projectId, previous.functionalPlanId());
        UiAutomationExecution retry = queueExecution(project.organizationId(), projectId, principal.userId(), previous.scenarioId(),
                timeout((int) Duration.between(previous.requestedAt(), previous.deadlineAt()).toSeconds()), null, plan,
                previous, previous.retryAttempt() + 1, Instant.now());
        audit.record(principal, project.organizationId(), projectId, "UI_AUTOMATION_EXECUTION_RETRY_QUEUED", "UI_AUTOMATION_EXECUTION",
                retry.id(), Map.of("retryOfExecutionId", previous.id(), "retryAttempt", retry.retryAttempt()));
        return retry;
    }

    @Transactional
    public UiAutomationExecution cancel(AuthenticatedPrincipal principal, String projectId, String executionId) {
        ProjectSummary project = writable(principal, projectId);
        UiAutomationExecution current = execution(projectId, executionId);
        if (terminal(current.status()) || "CANCELLING".equals(current.status())) return current;
        if (automation.cancelBeforeDispatch(projectId, executionId) > 0) {
            UiAutomationExecution cancelled = execution(projectId, executionId);
            onTerminal(cancelled);
            audit.record(principal, project.organizationId(), projectId, "UI_AUTOMATION_EXECUTION_CANCELLED", "UI_AUTOMATION_EXECUTION",
                    executionId, Map.of("taskId", cancelled.taskId(), "mode", "BEFORE_DISPATCH"));
            return cancelled;
        }
        if (automation.markCancelling(projectId, executionId) == 0) return execution(projectId, executionId);
        String commandId = UlidCreator.getUlid().toString();
        Instant requestedAt = Instant.now();
        automation.insertOutbox(commandId, executionId, "CANCEL", UiAutomationRunnerProtocol.CANCEL_STREAM,
                cancelPayload(commandId, current, requestedAt, "USER_REQUESTED"));
        UiAutomationExecution cancelling = execution(projectId, executionId);
        audit.record(principal, project.organizationId(), projectId, "UI_AUTOMATION_EXECUTION_CANCEL_REQUESTED", "UI_AUTOMATION_EXECUTION",
                executionId, Map.of("taskId", current.taskId(), "commandId", commandId));
        return cancelling;
    }

    public UiAutomationExecutionReport report(AuthenticatedPrincipal principal, String projectId, String executionId) {
        access.requireProjectRead(principal, projectId);
        UiAutomationExecution execution = execution(projectId, executionId);
        List<UiAutomationExecutionStepResult> steps = automation.findStepResults(projectId, executionId);
        int passed = (int) steps.stream().filter(step -> "PASSED".equals(step.outcome())).count();
        int failed = (int) steps.stream().filter(step -> "FAILED".equals(step.outcome())).count();
        long duration = steps.stream().mapToLong(UiAutomationExecutionStepResult::durationMillis).sum();
        return new UiAutomationExecutionReport(execution, new UiAutomationExecutionReport.Summary(steps.size(), passed, failed, duration),
                steps, automation.findArtifacts(projectId, executionId));
    }

    @Transactional
    public void applyRunnerEvent(UiAutomationRunnerProtocol.Event event) {
        String status = Map.of("ACCEPTED", "ACCEPTED", "RUNNING", "RUNNING", "SUCCEEDED", "SUCCEEDED", "FAILED", "FAILED",
                "CANCELLED", "CANCELLED", "REJECTED", "REJECTED").get(event.type());
        UiAutomationExecution execution = automation.findExecutionByTask(event.taskId(), event.executionId()).orElse(null);
        if (execution == null || status == null || automation.applyRunnerEvent(event, status) == 0) return;
        audit.recordSystem(execution.organizationId(), execution.projectId(), "UI_AUTOMATION_EXECUTION_EVENT_RECEIVED",
                "UI_AUTOMATION_EXECUTION", execution.id(), Map.of("taskId", execution.taskId(), "runnerId", event.runnerId(),
                        "type", event.type(), "code", event.code()));
        if (terminal(status)) onTerminal(execution(execution.projectId(), execution.id()));
    }

    @Transactional
    public void applyStepResult(UiAutomationRunnerProtocol.StepResult result) {
        UiAutomationExecution execution = automation.findExecutionByTask(result.taskId(), result.executionId()).orElse(null);
        if (execution == null || execution.runnerId() == null || !execution.runnerId().equals(result.runnerId())
                || !Set.of("ACCEPTED", "RUNNING", "CANCELLING", "SUCCEEDED", "FAILED", "CANCELLED").contains(execution.status())) return;
        automation.insertStepResult(execution, result);
    }

    @Transactional
    public UiAutomationRunnerRuntimeConfiguration runtimeConfiguration(String runnerId, String taskId, String executionId) {
        UiAutomationExecution execution = automation.findExecutionByTask(taskId, executionId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.FORBIDDEN, "Playwright Runner 运行时工件不可用"));
        UiAutomationManifest manifest = automation.findManifest(execution.projectId(), execution.id()).orElseThrow();
        UiAutomationManifest.BrowserProfile profile = manifest.snapshot().browserProfile();
        String baseUrl = targets.normalizeAndAssertAllowed(profile.baseUrl());
        if (automation.markRuntimeClaimed(taskId, executionId, runnerId) == 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Playwright Runner 运行时工件不可用");
        }
        audit.recordSystem(execution.organizationId(), execution.projectId(), "UI_AUTOMATION_RUNTIME_CONFIGURATION_ISSUED",
                "UI_AUTOMATION_EXECUTION", execution.id(), Map.of("taskId", taskId, "runnerId", runnerId,
                        "stepCount", manifest.snapshot().scenario().steps().size()));
        return new UiAutomationRunnerRuntimeConfiguration("testhub.playwright-runner.runtime-configuration", 1, execution.id(),
                execution.taskId(), execution.deadlineAt(), new UiAutomationRunnerRuntimeConfiguration.Browser(profile.browser(),
                baseUrl, profile.viewportWidth(), profile.viewportHeight()), new UiAutomationRunnerRuntimeConfiguration.Scenario(
                manifest.snapshot().scenario().failureStrategy(), manifest.snapshot().scenario().variables(), manifest.snapshot().scenario().steps()));
    }

    @Scheduled(fixedDelayString = "${testhub.ui-automation.execution-timeout-sweep-interval-ms:3000}")
    @Transactional
    public void expireDueExecutions() {
        for (UiAutomationExecution execution : automation.findExpiredActive()) {
            if (automation.markTimedOut(execution.id()) == 0) continue;
            automation.cancelPendingTaskOutbox(execution.id());
            if (automation.hasTaskPossiblyDispatched(execution.id()) && !automation.hasActiveCancellationCommand(execution.id())) {
                String commandId = UlidCreator.getUlid().toString();
                automation.insertOutbox(commandId, execution.id(), "CANCEL", UiAutomationRunnerProtocol.CANCEL_STREAM,
                        cancelPayload(commandId, execution, Instant.now(), "EXECUTION_TIMEOUT"));
            }
            onTerminal(execution(execution.projectId(), execution.id()));
            audit.recordSystem(execution.organizationId(), execution.projectId(), "UI_AUTOMATION_EXECUTION_TIMED_OUT",
                    "UI_AUTOMATION_EXECUTION", execution.id(), Map.of("taskId", execution.taskId()));
        }
    }

    private UiAutomationExecution queueExecution(String organizationId, String projectId, String requestedBy, String scenarioId,
                                                 int timeout, UiAutomationSchedule schedule, FunctionalTestPlan plan,
                                                 UiAutomationExecution retryOf, int retryAttempt, Instant requestedAt) {
        UiAutomationScenario scenario = scenario(projectId, scenarioId);
        UiAutomationBrowserProfile profile = profile(projectId, scenario.browserProfileId());
        if (!profile.enabled()) throw conflict("浏览器配置已停用，不能执行场景");
        String executionId = UlidCreator.getUlid().toString();
        String taskId = UlidCreator.getUlid().toString();
        Instant deadlineAt = requestedAt.plusSeconds(timeout);
        String manifestRef = "ui-automation/manifests/" + executionId + "/v1.json";
        automation.insertExecution(executionId, taskId, organizationId, projectId, scenario, profile,
                schedule == null ? null : schedule.id(), plan == null ? null : plan.id(), plan == null ? null : plan.name(),
                retryOf == null ? null : retryOf.id(), retryAttempt, manifestRef, requestedBy, requestedAt, deadlineAt);
        UiAutomationExecution execution = automation.findExecution(projectId, executionId).orElseThrow();
        UiAutomationManifest.Snapshot snapshot = manifest(projectId, scenario, profile);
        String manifestContent = json(snapshot);
        automation.insertManifest(UlidCreator.getUlid().toString(), execution, snapshot, manifestContent, sha256(manifestContent));
        automation.insertOutbox(taskId, executionId, "TASK", UiAutomationRunnerProtocol.TASK_STREAM, taskPayload(taskId, execution, requestedAt), requestedAt);
        return execution;
    }

    private void onTerminal(UiAutomationExecution execution) {
        if (execution.scheduleId() == null) return;
        UiAutomationSchedule schedule = schedules.findById(execution.scheduleId()).orElse(null);
        if (schedule == null) return;
        schedules.markOutcome(schedule.id(), execution.id(), execution.status());
        if (!Set.of("FAILED", "REJECTED", "TIMED_OUT").contains(execution.status()) || !schedule.enabled()
                || execution.retryAttempt() >= schedule.maxRetryAttempts()) return;
        FunctionalTestPlan plan = functionalPlan(execution.projectId(), schedule.functionalPlanId());
        Instant requestedAt = Instant.now().plusSeconds(schedule.retryBackoffSeconds());
        UiAutomationExecution retry = queueExecution(schedule.organizationId(), schedule.projectId(), schedule.createdBy(),
                schedule.scenarioId(), schedule.timeoutSeconds(), schedule, plan, execution, execution.retryAttempt() + 1, requestedAt);
        schedules.markTriggered(schedule.id(), retry.id(), requestedAt);
        audit.recordSystem(schedule.organizationId(), schedule.projectId(), "UI_AUTOMATION_EXECUTION_RETRY_QUEUED",
                "UI_AUTOMATION_EXECUTION", retry.id(), Map.of("scheduleId", schedule.id(), "retryOfExecutionId", execution.id(),
                        "retryAttempt", retry.retryAttempt(), "backoffSeconds", schedule.retryBackoffSeconds()));
    }

    private UiAutomationRepository.ProfileInput profileInput(ProfileRequest request) {
        if (request == null) throw rejected("浏览器配置不能为空");
        String name = required(request.name(), "配置名称", 120);
        String browser = request.browser() == null ? "" : request.browser().trim().toLowerCase(Locale.ROOT);
        if (!BROWSERS.contains(browser)) throw rejected("浏览器仅支持 chromium、firefox 或 webkit");
        String baseUrl = targets.normalizeAndAssertAllowed(request.baseUrl());
        int width = integer(request.viewportWidth(), 1440, 320, 3840, "视口宽度");
        int height = integer(request.viewportHeight(), 900, 320, 2160, "视口高度");
        return new UiAutomationRepository.ProfileInput(name, browser, baseUrl, width, height, request.enabled() == null || request.enabled());
    }

    private UiAutomationRepository.PageObjectInput pageObjectInput(PageObjectRequest request) {
        if (request == null) throw rejected("页面对象不能为空");
        String selector = required(request.selectorValue(), "定位器", 1024);
        if (selector.contains("\r") || selector.contains("\n")) throw rejected("定位器不能包含换行");
        return new UiAutomationRepository.PageObjectInput(required(request.name(), "页面对象名称", 120), selector,
                optional(request.description(), 1000));
    }

    private UiAutomationRepository.ScenarioInput scenarioInput(String projectId, ScenarioRequest request) {
        if (request == null) throw rejected("Web UI 场景不能为空");
        profile(projectId, required(request.browserProfileId(), "浏览器配置", 26));
        String strategy = request.failureStrategy() == null ? "STOP" : request.failureStrategy().trim().toUpperCase(Locale.ROOT);
        if (!FAILURE_STRATEGIES.contains(strategy)) throw rejected("失败策略仅支持 STOP 或 CONTINUE");
        return new UiAutomationRepository.ScenarioInput(request.browserProfileId().trim(), required(request.name(), "场景名称", 160),
                optional(request.description(), 2000), strategy, variables(request.variables()), steps(projectId, request.steps()));
    }

    private Map<String, String> variables(Map<String, String> source) {
        if (source == null || source.isEmpty()) return Map.of();
        if (source.size() > 50) throw rejected("场景变量不能超过 50 个");
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            String value = entry.getValue() == null ? "" : entry.getValue();
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]{0,63}") || value.length() > 4096 || value.contains("\r") || value.contains("\n")) {
                throw rejected("场景变量格式不合法");
            }
            result.put(key, value);
        }
        return Map.copyOf(result);
    }

    private List<UiAutomationStep> steps(String projectId, List<UiAutomationStep> source) {
        if (source == null || source.isEmpty() || source.size() > 300) throw rejected("场景步骤数量应在 1 到 300 之间");
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        List<UiAutomationStep> result = new ArrayList<>();
        for (UiAutomationStep step : source) {
            if (step == null) throw rejected("场景步骤不能为空");
            String id = required(step.id(), "步骤标识", 64);
            if (!id.matches("[A-Za-z0-9_-]{1,64}") || !ids.add(id)) throw rejected("步骤标识必须唯一且仅包含字母、数字、下划线或连字符");
            String type = step.type() == null ? "" : step.type().trim().toUpperCase(Locale.ROOT);
            if (!STEP_TYPES.contains(type)) throw rejected("存在不支持的 Web UI 步骤类型");
            String pageObjectId = blankToNull(step.pageObjectId());
            String selector = blankToNull(step.selector());
            if (pageObjectId != null) selector = pageObject(projectId, pageObjectId).selectorValue();
            if (needsSelector(type) && (selector == null || selector.length() > 1024 || selector.contains("\r") || selector.contains("\n"))) {
                throw rejected("步骤 " + id + " 缺少有效定位器");
            }
            String value = blankToNull(step.value());
            String url = blankToNull(step.url());
            String expected = blankToNull(step.expectedText());
            if ("NAVIGATE".equals(type) && (url == null || url.length() > 2048 || url.contains("\r") || url.contains("\n"))) {
                throw rejected("导航步骤必须填写目标路径");
            }
            if ("FILL".equals(type) && (value == null || value.length() > 4096 || value.contains("\r") || value.contains("\n"))) {
                throw rejected("填写步骤必须包含不超过 4096 字符的值");
            }
            if ("ASSERT_TEXT".equals(type) && (expected == null || expected.length() > 4096 || expected.contains("\r") || expected.contains("\n"))) {
                throw rejected("文本断言必须包含期望文本");
            }
            Integer timeout = step.timeoutMillis();
            if ("WAIT".equals(type)) {
                if (timeout == null || timeout < 1 || timeout > 60_000) throw rejected("等待步骤时长应在 1 到 60000 毫秒之间");
            } else if (timeout != null && (timeout < 100 || timeout > 60_000)) throw rejected("步骤超时时间应在 100 到 60000 毫秒之间");
            result.add(new UiAutomationStep(id, type, required(step.name(), "步骤名称", 160), selector, pageObjectId,
                    value, url, expected, timeout));
        }
        return List.copyOf(result);
    }

    private UiAutomationManifest.Snapshot manifest(String projectId, UiAutomationScenario scenario, UiAutomationBrowserProfile profile) {
        List<UiAutomationManifest.ResolvedStep> steps = scenario.steps().stream().map(step -> new UiAutomationManifest.ResolvedStep(
                step.id(), step.type(), step.name(), step.pageObjectId() == null ? step.selector() : pageObject(projectId, step.pageObjectId()).selectorValue(),
                step.value(), step.url(), step.expectedText(), step.timeoutMillis())).toList();
        return new UiAutomationManifest.Snapshot("testhub.ui-automation.manifest", 1,
                new UiAutomationManifest.BrowserProfile(profile.name(), profile.browser(), profile.baseUrl(), profile.viewportWidth(), profile.viewportHeight()),
                new UiAutomationManifest.Scenario(scenario.name(), scenario.failureStrategy(), scenario.variables(), steps));
    }

    private Map<String, String> taskPayload(String taskId, UiAutomationExecution execution, Instant requestedAt) {
        return Map.ofEntries(Map.entry("contract", UiAutomationRunnerProtocol.TASK_CONTRACT),
                Map.entry("protocolVersion", UiAutomationRunnerProtocol.VERSION), Map.entry("taskId", taskId),
                Map.entry("executionId", execution.id()), Map.entry("organizationId", execution.organizationId()),
                Map.entry("projectId", execution.projectId()), Map.entry("scenarioId", execution.scenarioId()),
                Map.entry("scenarioVersion", String.valueOf(execution.scenarioVersion())), Map.entry("requestedAt", requestedAt.toString()),
                Map.entry("deadlineAt", execution.deadlineAt().toString()), Map.entry("manifestRef", execution.manifestRef()),
                Map.entry("traceId", taskId));
    }

    private Map<String, String> cancelPayload(String commandId, UiAutomationExecution execution, Instant requestedAt, String reasonCode) {
        return Map.ofEntries(Map.entry("contract", UiAutomationRunnerProtocol.CANCEL_CONTRACT),
                Map.entry("protocolVersion", UiAutomationRunnerProtocol.VERSION), Map.entry("commandId", commandId),
                Map.entry("taskId", execution.taskId()), Map.entry("executionId", execution.id()),
                Map.entry("requestedAt", requestedAt.toString()), Map.entry("reasonCode", reasonCode));
    }

    private boolean needsSelector(String type) { return Set.of("FILL", "CLICK", "ASSERT_VISIBLE", "ASSERT_TEXT").contains(type); }
    private UiAutomationBrowserProfile profile(String projectId, String id) { return automation.findProfile(projectId, id).orElseThrow(() -> notFound("浏览器配置不存在")); }
    private UiAutomationPageObject pageObject(String projectId, String id) { return automation.findPageObject(projectId, id).orElseThrow(() -> notFound("页面对象不存在")); }
    private UiAutomationScenario scenario(String projectId, String id) { return automation.findScenario(projectId, id).orElseThrow(() -> notFound("Web UI 场景不存在")); }
    private UiAutomationExecution execution(String projectId, String id) { return automation.findExecution(projectId, id).orElseThrow(() -> notFound("Web UI 执行记录不存在")); }
    private FunctionalTestPlan functionalPlan(String projectId, String id) {
        if (id == null || id.isBlank()) return null;
        return functionalPlans.findPlan(projectId, id).orElseThrow(() -> notFound("功能测试计划不存在"));
    }
    private boolean terminal(String status) { return Set.of("SUCCEEDED", "FAILED", "REJECTED", "CANCELLED", "TIMED_OUT").contains(status); }
    private ProjectSummary writable(AuthenticatedPrincipal principal, String projectId) { return access.requireProjectParameterWrite(principal, projectId, WRITERS); }
    private int timeout(Integer value) { return integer(value, 300, 10, 3600, "执行超时"); }
    private int integer(Integer value, int fallback, int min, int max, String label) {
        int result = value == null ? fallback : value;
        if (result < min || result > max) throw rejected(label + "应在 " + min + " 到 " + max + " 之间");
        return result;
    }
    private String required(String value, String label, int max) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > max) throw rejected(label + "长度应在 1 到 " + max + " 字符之间");
        return normalized;
    }
    private String optional(String value, int max) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > max) throw rejected("说明不能超过 " + max + " 字符");
        return normalized;
    }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String json(Object value) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("无法生成 Web UI 执行清单", exception); }
    }
    private String sha256(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException("无法校验 Web UI 执行清单", exception); }
    }
    private ResponseStatusException rejected(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException notFound(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }

    public record ProfileRequest(String name, String browser, String baseUrl, Integer viewportWidth, Integer viewportHeight, Boolean enabled) { }
    public record PageObjectRequest(String name, String selectorValue, String description) { }
    public record ScenarioRequest(String browserProfileId, String name, String description, String failureStrategy,
                                  Map<String, String> variables, List<UiAutomationStep> steps) { }
}
