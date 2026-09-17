package io.testhub.platform.functional;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/functional")
public class FunctionalTestExecutionController {
    private final FunctionalTestExecutionService executions;

    public FunctionalTestExecutionController(FunctionalTestExecutionService executions) {
        this.executions = executions;
    }

    @GetMapping("/plans")
    public ApiResponse<List<FunctionalTestPlan>> plans(Authentication authentication, @PathVariable String projectId,
                                                        @RequestParam(required = false) String catalogId) {
        return ApiResponse.of(executions.listPlans(principal(authentication), projectId, catalogId));
    }

    @PostMapping("/plans")
    public ApiResponse<FunctionalTestPlan> createPlan(Authentication authentication, @PathVariable String projectId,
                                                       @Valid @RequestBody PlanRequest request) {
        return ApiResponse.of(executions.createPlan(principal(authentication), projectId, request.toInput()));
    }

    @PutMapping("/plans/{planId}")
    public ApiResponse<FunctionalTestPlan> updatePlan(Authentication authentication, @PathVariable String projectId,
                                                       @PathVariable String planId, @Valid @RequestBody PlanRequest request) {
        return ApiResponse.of(executions.updatePlan(principal(authentication), projectId, planId, request.toInput()));
    }

    @DeleteMapping("/plans/{planId}")
    public ResponseEntity<Void> deletePlan(Authentication authentication, @PathVariable String projectId, @PathVariable String planId) {
        executions.deletePlan(principal(authentication), projectId, planId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/plans/{planId}")
    public ApiResponse<FunctionalTestPlan> plan(Authentication authentication, @PathVariable String projectId,
                                                 @PathVariable String planId) {
        return ApiResponse.of(executions.getPlan(principal(authentication), projectId, planId));
    }

    @GetMapping("/plans/{planId}/cases")
    public ApiResponse<List<FunctionalTestPlanCase>> planCases(Authentication authentication, @PathVariable String projectId,
                                                                @PathVariable String planId) {
        return ApiResponse.of(executions.listPlanCases(principal(authentication), projectId, planId));
    }

    @PostMapping("/plans/{planId}/runs")
    public ApiResponse<FunctionalTestRun> createRun(Authentication authentication, @PathVariable String projectId,
                                                     @PathVariable String planId, @Valid @RequestBody RunRequest request) {
        return ApiResponse.of(executions.createRun(principal(authentication), projectId, planId, request.name()));
    }

    @GetMapping("/runs")
    public ApiResponse<List<FunctionalTestRun>> runs(Authentication authentication, @PathVariable String projectId,
                                                     @RequestParam(required = false) String planId) {
        return ApiResponse.of(executions.listRuns(principal(authentication), projectId, planId));
    }

    @GetMapping("/runs/{runId}")
    public ApiResponse<FunctionalTestRun> run(Authentication authentication, @PathVariable String projectId,
                                               @PathVariable String runId) {
        return ApiResponse.of(executions.getRun(principal(authentication), projectId, runId));
    }

    @GetMapping("/runs/{runId}/cases")
    public ApiResponse<List<FunctionalTestRunCase>> runCases(Authentication authentication, @PathVariable String projectId,
                                                              @PathVariable String runId) {
        return ApiResponse.of(executions.listRunCases(principal(authentication), projectId, runId));
    }

    @GetMapping("/run-cases/{runCaseId}/steps")
    public ApiResponse<List<FunctionalTestRunStep>> runSteps(Authentication authentication, @PathVariable String projectId,
                                                              @PathVariable String runCaseId) {
        return ApiResponse.of(executions.listRunSteps(principal(authentication), projectId, runCaseId));
    }

    @PutMapping("/run-cases/{runCaseId}")
    public ApiResponse<FunctionalTestRunCase> updateRunCase(Authentication authentication, @PathVariable String projectId,
                                                             @PathVariable String runCaseId, @Valid @RequestBody RunCaseRequest request) {
        return ApiResponse.of(executions.updateRunCase(principal(authentication), projectId, runCaseId, request.toInput()));
    }

    @PutMapping("/run-steps/{stepId}")
    public ApiResponse<FunctionalTestRunStep> updateRunStep(Authentication authentication, @PathVariable String projectId,
                                                             @PathVariable String stepId, @Valid @RequestBody RunStepRequest request) {
        return ApiResponse.of(executions.updateRunStep(principal(authentication), projectId, stepId, request.toInput()));
    }

    private AuthenticatedPrincipal principal(Authentication authentication) {
        return (AuthenticatedPrincipal) authentication.getPrincipal();
    }

    public record PlanRequest(String catalogId, @NotBlank @Size(max = 40) String planCode, @NotBlank @Size(max = 200) String name,
                              String status, @Size(max = 20_000) String description, List<String> caseIds) {
        FunctionalTestExecutionService.PlanInput toInput() { return new FunctionalTestExecutionService.PlanInput(catalogId, planCode, name, status, description, caseIds); }
    }

    public record RunRequest(@Size(max = 200) String name) { }
    public record RunCaseRequest(@NotBlank String status, @Size(max = 20_000) String note) {
        FunctionalTestExecutionService.RunCaseInput toInput() { return new FunctionalTestExecutionService.RunCaseInput(status, note); }
    }
    public record RunStepRequest(@NotBlank String status, @Size(max = 20_000) String actualResult) {
        FunctionalTestExecutionService.RunStepInput toInput() { return new FunctionalTestExecutionService.RunStepInput(status, actualResult); }
    }
}
