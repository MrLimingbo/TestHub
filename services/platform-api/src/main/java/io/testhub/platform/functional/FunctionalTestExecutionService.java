package io.testhub.platform.functional;

import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ProjectSummary;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FunctionalTestExecutionService {
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private static final Set<String> PLAN_STATUSES = Set.of("DRAFT", "READY", "ARCHIVED");
    private static final Set<String> RUN_CASE_STATUSES = Set.of("NOT_RUN", "PASSED", "FAILED", "BLOCKED");
    private static final Set<String> STEP_STATUSES = Set.of("NOT_RUN", "PASSED", "FAILED", "BLOCKED");
    private final FunctionalTestExecutionRepository executions;
    private final FunctionalCaseRepository cases;
    private final FunctionalCatalogRepository catalogs;
    private final AccessService access;
    private final AuditService audit;

    public FunctionalTestExecutionService(FunctionalTestExecutionRepository executions, FunctionalCaseRepository cases,
                                          FunctionalCatalogRepository catalogs,
                                          AccessService access, AuditService audit) {
        this.executions = executions;
        this.cases = cases;
        this.catalogs = catalogs;
        this.access = access;
        this.audit = audit;
    }

    public List<FunctionalTestPlan> listPlans(AuthenticatedPrincipal principal, String projectId, String catalogId) {
        access.requireProjectRead(principal, projectId);
        String normalizedCatalogId = blankToNull(catalogId);
        validatePlanCatalog(projectId, normalizedCatalogId);
        return executions.findPlans(projectId, normalizedCatalogId);
    }

    public FunctionalTestPlan getPlan(AuthenticatedPrincipal principal, String projectId, String planId) {
        access.requireProjectRead(principal, projectId);
        return requirePlan(projectId, planId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FunctionalTestPlan createPlan(AuthenticatedPrincipal principal, String projectId, PlanInput input) {
        ProjectSummary project = writeAccess(principal, projectId);
        validatePlan(input);
        String code = normalizeCode(input.planCode(), "计划编号");
        String name = required(input.name(), 200, "计划名称");
        String status = normalizePlanStatus(input.status());
        String catalogId = blankToNull(input.catalogId());
        validatePlanCatalog(projectId, catalogId);
        List<String> caseIds = input.caseIds() == null ? List.of() : input.caseIds().stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
        validateCases(projectId, caseIds);
        String id = UlidCreator.getUlid().toString();
        try {
            executions.insertPlan(id, project.organizationId(), projectId, catalogId, code, name, status, text(input.description()), principal.userId());
            replacePlanCases(id, caseIds);
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "测试计划编号已存在或用例无效", exception);
        }
        audit.record(principal, project.organizationId(), projectId, "FUNCTIONAL_PLAN_CREATED", "FUNCTIONAL_TEST_PLAN", id,
                Map.of("planCode", code, "caseCount", caseIds.size()));
        return requirePlan(projectId, id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FunctionalTestPlan updatePlan(AuthenticatedPrincipal principal, String projectId, String planId, PlanInput input) {
        ProjectSummary project = writeAccess(principal, projectId);
        validatePlan(input);
        requirePlan(projectId, planId);
        String name = required(input.name(), 200, "计划名称");
        String status = normalizePlanStatus(input.status());
        String catalogId = blankToNull(input.catalogId());
        validatePlanCatalog(projectId, catalogId);
        List<String> caseIds = input.caseIds() == null ? List.of() : input.caseIds().stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
        validateCases(projectId, caseIds);
        executions.updatePlan(projectId, planId, catalogId, name, status, text(input.description()), principal.userId());
        replacePlanCases(planId, caseIds);
        audit.record(principal, project.organizationId(), projectId, "FUNCTIONAL_PLAN_UPDATED", "FUNCTIONAL_TEST_PLAN", planId, Map.of());
        return requirePlan(projectId, planId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deletePlan(AuthenticatedPrincipal principal, String projectId, String planId) {
        ProjectSummary project = writeAccess(principal, projectId);
        requirePlan(projectId, planId);
        executions.deleteRunsByPlan(projectId, planId);
        executions.clearPlanCases(planId);
        if (executions.deletePlan(projectId, planId) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "测试计划不存在");
        audit.record(principal, project.organizationId(), projectId, "FUNCTIONAL_PLAN_DELETED", "FUNCTIONAL_TEST_PLAN", planId, Map.of());
    }

    public List<FunctionalTestPlanCase> listPlanCases(AuthenticatedPrincipal principal, String projectId, String planId) {
        access.requireProjectRead(principal, projectId);
        requirePlan(projectId, planId);
        return executions.findPlanCases(projectId, planId);
    }

    public List<FunctionalTestRun> listRuns(AuthenticatedPrincipal principal, String projectId, String planId) {
        access.requireProjectRead(principal, projectId);
        if (planId != null && !planId.isBlank()) requirePlan(projectId, planId);
        return executions.findRuns(projectId, blankToNull(planId));
    }

    public FunctionalTestRun getRun(AuthenticatedPrincipal principal, String projectId, String runId) {
        access.requireProjectRead(principal, projectId);
        return requireRun(projectId, runId);
    }

    public List<FunctionalTestRun> recentRuns(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectRead(principal, projectId);
        return executions.findRecentRuns(projectId);
    }

    public List<FunctionalTestRunCase> listRunCases(AuthenticatedPrincipal principal, String projectId, String runId) {
        access.requireProjectRead(principal, projectId);
        requireRun(projectId, runId);
        return executions.findRunCases(projectId, runId);
    }

    public List<FunctionalTestRunStep> listRunSteps(AuthenticatedPrincipal principal, String projectId, String runCaseId) {
        access.requireProjectRead(principal, projectId);
        requireRunCase(projectId, runCaseId);
        return executions.findRunSteps(projectId, runCaseId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FunctionalTestRun createRun(AuthenticatedPrincipal principal, String projectId, String planId, String name) {
        ProjectSummary project = writeAccess(principal, projectId);
        FunctionalTestPlan plan = requirePlan(projectId, planId);
        List<FunctionalTestPlanCase> planCases = executions.findPlanCases(projectId, planId);
        if (planCases.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "测试计划至少需要包含一个用例");
        String runName = name == null || name.isBlank() ? plan.name() + " · 执行批次" : required(name, 200, "执行批次名称");
        String runId = UlidCreator.getUlid().toString();
        executions.insertRun(runId, project.organizationId(), projectId, planId, runName, "NOT_STARTED", principal.userId());
        for (FunctionalTestPlanCase planCase : planCases) {
            String runCaseId = UlidCreator.getUlid().toString();
            executions.insertRunCase(runCaseId, runId, planCase.caseId(), "NOT_RUN", "");
            FunctionalCase testCase = cases.findById(projectId, planCase.caseId()).orElseThrow();
            String[] steps = testCase.steps().split("\\R");
            for (int index = 0; index < steps.length; index++) {
                if (!steps[index].isBlank()) executions.insertRunStep(UlidCreator.getUlid().toString(), runCaseId, index + 1, steps[index].trim());
            }
        }
        audit.record(principal, project.organizationId(), projectId, "FUNCTIONAL_RUN_CREATED", "FUNCTIONAL_TEST_RUN", runId,
                Map.of("planId", planId, "caseCount", planCases.size()));
        return requireRun(projectId, runId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FunctionalTestRunCase updateRunCase(AuthenticatedPrincipal principal, String projectId, String runCaseId,
                                                RunCaseInput input) {
        ProjectSummary project = writeAccess(principal, projectId);
        lockRunForCase(projectId, runCaseId);
        FunctionalTestRunCase runCase = requireRunCase(projectId, runCaseId);
        String status = normalizeRunCaseStatus(input == null ? null : input.status());
        if (status.equals("PASSED") && executions.findRunSteps(projectId, runCaseId).stream().anyMatch(step -> !step.status().equals("PASSED"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "所有步骤均通过后才能将用例标记为通过");
        }
        executions.updateRunCase(runCaseId, status, text(input == null ? null : input.note()), principal.userId(), Instant.now());
        recalculateRun(runCase.runId());
        audit.record(principal, project.organizationId(), projectId, "FUNCTIONAL_RUN_CASE_UPDATED", "FUNCTIONAL_TEST_RUN_CASE", runCaseId,
                Map.of("status", status));
        return executions.findRunCase(projectId, runCaseId).orElseThrow();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FunctionalTestRunStep updateRunStep(AuthenticatedPrincipal principal, String projectId, String stepId,
                                               RunStepInput input) {
        ProjectSummary project = writeAccess(principal, projectId);
        String status = normalizeStepStatus(input == null ? null : input.status());
        String runCaseId = findRunCaseId(projectId, stepId);
        lockRunForCase(projectId, runCaseId);
        FunctionalTestRunCase runCase = requireRunCase(projectId, runCaseId);
        if (runCase.status().equals("PASSED") && !status.equals("PASSED")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用例已标记通过，请先调整用例结果再修改步骤状态");
        }
        if (executions.updateRunStep(projectId, stepId, status, text(input == null ? null : input.actualResult()), principal.userId()) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "执行步骤不存在");
        }
        audit.record(principal, project.organizationId(), projectId, "FUNCTIONAL_RUN_STEP_UPDATED", "FUNCTIONAL_TEST_RUN_STEP", stepId,
                Map.of("status", status));
        FunctionalTestRunStep step = executions.findRunSteps(projectId, runCaseId).stream()
                .filter(candidate -> candidate.id().equals(stepId)).findFirst().orElseThrow();
        return step;
    }

    private void recalculateRun(String runId) {
        // The parent row is locked by every result writer, so the aggregate sees all earlier committed results.
        List<FunctionalTestRunCase> runCases = executions.findRunCasesByRunId(runId);
        boolean anyTerminal = runCases.stream().anyMatch(item -> !item.status().equals("NOT_RUN"));
        boolean allTerminal = !runCases.isEmpty() && runCases.stream().allMatch(item -> !item.status().equals("NOT_RUN"));
        String status = allTerminal ? "COMPLETED" : anyTerminal ? "RUNNING" : "NOT_STARTED";
        executions.updateRunStatus(runId, status, anyTerminal ? Instant.now() : null, allTerminal ? Instant.now() : null);
    }

    private void lockRunForCase(String projectId, String runCaseId) {
        // READ_COMMITTED also prevents a pre-lock permission query from pinning an older MySQL snapshot.
        if (executions.lockRunForCase(projectId, runCaseId).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "执行用例不存在");
    }

    private String findRunCaseId(String projectId, String stepId) {
        return executions.findRunCaseIdByStep(projectId, stepId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "执行步骤不存在"));
    }

    private void replacePlanCases(String planId, List<String> caseIds) {
        executions.clearPlanCases(planId);
        for (int index = 0; index < caseIds.size(); index++) {
            executions.insertPlanCase(UlidCreator.getUlid().toString(), planId, caseIds.get(index), index);
        }
    }

    private void validateCases(String projectId, List<String> caseIds) {
        for (String caseId : caseIds) cases.findById(projectId, caseId).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "计划包含不存在的用例"));
    }

    private void validatePlanCatalog(String projectId, String catalogId) {
        if (catalogId == null) return;
        FunctionalCatalog catalog = catalogs.findById(projectId, catalogId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "计划目录不存在"));
        if (!catalog.scope().equals("PLANS")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "计划目录类型不匹配");
    }

    private FunctionalTestPlan requirePlan(String projectId, String planId) {
        return executions.findPlan(projectId, planId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "测试计划不存在"));
    }

    private FunctionalTestRun requireRun(String projectId, String runId) {
        return executions.findRun(projectId, runId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "执行批次不存在"));
    }

    private FunctionalTestRunCase requireRunCase(String projectId, String runCaseId) {
        return executions.findRunCase(projectId, runCaseId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "执行用例不存在"));
    }

    private ProjectSummary writeAccess(AuthenticatedPrincipal principal, String projectId) {
        var project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
        catalogs.lockProject(projectId);
        return project;
    }

    private void validatePlan(PlanInput input) {
        if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "测试计划参数不能为空");
        required(input.planCode(), 40, "计划编号"); required(input.name(), 200, "计划名称");
    }

    private String normalizeCode(String value, String label) {
        String normalized = required(value, 40, label).toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z][A-Z0-9_-]{1,39}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "格式不合法");
        return normalized;
    }

    private String normalizePlanStatus(String value) {
        String normalized = value == null ? "DRAFT" : value.trim().toUpperCase(Locale.ROOT);
        if (!PLAN_STATUSES.contains(normalized)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "计划状态不合法");
        return normalized;
    }

    private String normalizeRunCaseStatus(String value) {
        String normalized = value == null ? "NOT_RUN" : value.trim().toUpperCase(Locale.ROOT);
        if (!RUN_CASE_STATUSES.contains(normalized)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "执行结果不合法");
        return normalized;
    }

    private String normalizeStepStatus(String value) {
        String normalized = value == null ? "NOT_RUN" : value.trim().toUpperCase(Locale.ROOT);
        if (!STEP_STATUSES.contains(normalized)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "步骤结果不合法");
        return normalized;
    }

    private String required(String value, int max, String label) {
        if (value == null || value.isBlank() || value.trim().length() > max) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "不能为空且不能超过" + max + "个字符");
        return value.trim();
    }

    private String text(String value) { return value == null ? "" : value.trim(); }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    public record PlanInput(String catalogId, String planCode, String name, String status, String description, List<String> caseIds) { }
    public record RunCaseInput(String status, String note) { }
    public record RunStepInput(String status, String actualResult) { }
}
