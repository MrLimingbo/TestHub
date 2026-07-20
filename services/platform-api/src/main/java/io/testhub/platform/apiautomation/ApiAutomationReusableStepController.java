package io.testhub.platform.apiautomation;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/api-automation/reusable-steps")
public class ApiAutomationReusableStepController {
    private final ApiAutomationReusableStepService reusableSteps;

    public ApiAutomationReusableStepController(ApiAutomationReusableStepService reusableSteps) {
        this.reusableSteps = reusableSteps;
    }

    @GetMapping
    public ApiResponse<List<ApiAutomationReusableStep>> list(Authentication authentication, @PathVariable String projectId) {
        return ApiResponse.of(reusableSteps.list(principal(authentication), projectId));
    }

    @PostMapping
    public ApiResponse<ApiAutomationReusableStepDetail> create(Authentication authentication, @PathVariable String projectId,
                                                                 @Valid @RequestBody ReusableStepRequest request) {
        return ApiResponse.of(reusableSteps.create(principal(authentication), projectId, input(request, null)));
    }

    @GetMapping("/{reusableStepId}")
    public ApiResponse<ApiAutomationReusableStepDetail> detail(Authentication authentication, @PathVariable String projectId,
                                                                 @PathVariable String reusableStepId) {
        return ApiResponse.of(reusableSteps.detail(principal(authentication), projectId, reusableStepId));
    }

    @PutMapping("/{reusableStepId}")
    public ApiResponse<ApiAutomationReusableStepDetail> update(Authentication authentication, @PathVariable String projectId,
                                                                 @PathVariable String reusableStepId,
                                                                 @Valid @RequestBody ReusableStepUpdateRequest request) {
        return ApiResponse.of(reusableSteps.update(principal(authentication), projectId, reusableStepId,
                input(request.reusableStep(), request.expectedVersion())));
    }

    private ApiAutomationReusableStepService.ReusableStepInput input(ReusableStepRequest request, Integer expectedVersion) {
        return new ApiAutomationReusableStepService.ReusableStepInput(request.name(), request.description(),
                request.steps().stream().map(step -> new AutomationStep(step.id(), step.type(), step.name(), step.config())).toList(),
                expectedVersion);
    }

    private AuthenticatedPrincipal principal(Authentication authentication) {
        return (AuthenticatedPrincipal) authentication.getPrincipal();
    }

    public record ReusableStepRequest(@NotBlank @Size(max = 160) String name, @Size(max = 1200) String description,
                                      @NotEmpty @Size(max = 300) List<@Valid StepRequest> steps) { }
    public record ReusableStepUpdateRequest(@NotNull @Valid ReusableStepRequest reusableStep, @Min(1) Integer expectedVersion) { }
    public record StepRequest(@NotBlank @Size(max = 64) String id, @NotBlank @Size(max = 24) String type,
                              @NotBlank @Size(max = 160) String name, @Size(max = 50) Map<String, Object> config) { }
}
