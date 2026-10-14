package io.testhub.platform.apiautomation;

import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ProjectSummary;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ApiAutomationReusableStepService {
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private static final int MAX_REUSABLE_STEPS_PER_PROJECT = 100;
    private final ApiAutomationReusableStepRepository reusableSteps;
    private final ApiAutomationScenarioService scenarioSteps;
    private final AccessService access;
    private final AuditService audit;

    public ApiAutomationReusableStepService(ApiAutomationReusableStepRepository reusableSteps,
                                            ApiAutomationScenarioService scenarioSteps, AccessService access,
                                            AuditService audit) {
        this.reusableSteps = reusableSteps;
        this.scenarioSteps = scenarioSteps;
        this.access = access;
        this.audit = audit;
    }

    public List<ApiAutomationReusableStep> list(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectRead(principal, projectId);
        return reusableSteps.findByProject(projectId);
    }

    public ApiAutomationReusableStepDetail detail(AuthenticatedPrincipal principal, String projectId, String reusableStepId) {
        access.requireProjectRead(principal, projectId);
        return require(projectId, reusableStepId);
    }

    @Transactional
    public ApiAutomationReusableStepDetail create(AuthenticatedPrincipal principal, String projectId, ReusableStepInput input) {
        ProjectSummary project = writeAccess(principal, projectId);
        if (reusableSteps.countByProject(projectId) >= MAX_REUSABLE_STEPS_PER_PROJECT) {
            throw conflict("每个项目最多创建 100 个公共步骤");
        }
        Payload payload = payload(input);
        String id = UlidCreator.getUlid().toString();
        try {
            reusableSteps.insert(id, project.organizationId(), projectId, payload.name(), payload.description(),
                    reusableSteps.json(payload.steps()), principal.userId());
        } catch (DataIntegrityViolationException exception) {
            throw conflict("当前项目已存在同名公共步骤");
        }
        ApiAutomationReusableStepDetail result = require(projectId, id);
        saveRevision(project, result, principal);
        audit.record(principal, project.organizationId(), projectId, "API_AUTOMATION_REUSABLE_STEP_CREATED",
                "API_AUTOMATION_REUSABLE_STEP", id, Map.of("name", result.name(), "version", result.version(),
                        "stepCount", result.steps().size()));
        return result;
    }

    @Transactional
    public ApiAutomationReusableStepDetail update(AuthenticatedPrincipal principal, String projectId, String reusableStepId,
                                                   ReusableStepInput input) {
        ProjectSummary project = writeAccess(principal, projectId);
        if (input == null || input.expectedVersion() == null || input.expectedVersion() < 1) {
            throw rejected("保存公共步骤时必须提供当前版本号");
        }
        Payload payload = payload(input);
        int updated;
        try {
            updated = reusableSteps.update(projectId, reusableStepId, input.expectedVersion(), payload.name(),
                    payload.description(), reusableSteps.json(payload.steps()));
        } catch (DataIntegrityViolationException exception) {
            throw conflict("当前项目已存在同名公共步骤");
        }
        if (updated == 0) {
            ApiAutomationReusableStepDetail current = reusableSteps.findDetail(projectId, reusableStepId)
                    .orElseThrow(() -> notFound("公共步骤不存在"));
            throw conflict("公共步骤已被其他成员更新，请刷新后再保存（当前版本 v" + current.version() + "）");
        }
        ApiAutomationReusableStepDetail result = require(projectId, reusableStepId);
        saveRevision(project, result, principal);
        audit.record(principal, project.organizationId(), projectId, "API_AUTOMATION_REUSABLE_STEP_UPDATED",
                "API_AUTOMATION_REUSABLE_STEP", reusableStepId, Map.of("name", result.name(), "version", result.version(),
                        "stepCount", result.steps().size()));
        return result;
    }

    void validateSyncInput(ReusableStepInput input) { payload(input); }

    ApiAutomationReusableStepRevisionSnapshot normalizedSyncSnapshot(ApiAutomationReusableStepRevisionSnapshot snapshot) {
        var value = payload(new ReusableStepInput(snapshot.name(), snapshot.description(), snapshot.steps(), null));
        return new ApiAutomationReusableStepRevisionSnapshot(value.name(), value.description(), value.steps());
    }

    private Payload payload(ReusableStepInput input) {
        if (input == null) throw rejected("公共步骤参数不能为空");
        String name = required(input.name(), "公共步骤名称", 160);
        String description = optional(input.description(), "公共步骤说明", 1200);
        List<AutomationStep> steps = scenarioSteps.validateReusableSteps(input.steps());
        if (reusableSteps.json(steps).length() > 256 * 1024) throw rejected("公共步骤配置不能超过 256 KB");
        return new Payload(name, description, steps);
    }

    private void saveRevision(ProjectSummary project, ApiAutomationReusableStepDetail result,
                              AuthenticatedPrincipal principal) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", result.name());
        snapshot.put("description", result.description());
        snapshot.put("steps", result.steps());
        reusableSteps.insertRevision(UlidCreator.getUlid().toString(), result.id(), project.organizationId(), project.id(),
                result.version(), reusableSteps.json(snapshot), principal.userId());
    }

    private ProjectSummary writeAccess(AuthenticatedPrincipal principal, String projectId) {
        return access.requireProjectParameterWrite(principal, projectId, WRITERS);
    }

    private ApiAutomationReusableStepDetail require(String projectId, String reusableStepId) {
        return reusableSteps.findDetail(projectId, reusableStepId).orElseThrow(() -> notFound("公共步骤不存在"));
    }

    private String required(String value, String label, int maximum) {
        if (value == null || value.isBlank()) throw rejected(label + "不能为空");
        String result = value.trim();
        if (result.length() > maximum) throw rejected(label + "不能超过 " + maximum + " 个字符");
        return result;
    }

    private String optional(String value, String label, int maximum) {
        String result = value == null ? "" : value.trim();
        if (result.length() > maximum) throw rejected(label + "不能超过 " + maximum + " 个字符");
        return result;
    }

    private ResponseStatusException rejected(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException notFound(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }

    public record ReusableStepInput(String name, String description, List<AutomationStep> steps, Integer expectedVersion) { }
    private record Payload(String name, String description, List<AutomationStep> steps) { }
}
