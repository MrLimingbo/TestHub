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
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/api-automation/scenarios")
public class ApiAutomationScenarioController {
    private final ApiAutomationScenarioService scenarios;

    public ApiAutomationScenarioController(ApiAutomationScenarioService scenarios) {
        this.scenarios = scenarios;
    }

    @GetMapping
    public ApiResponse<List<ApiAutomationScenario>> list(Authentication authentication, @PathVariable String projectId) {
        return ApiResponse.of(scenarios.list(principal(authentication), projectId));
    }

    @PostMapping
    public ApiResponse<ApiAutomationScenarioDetail> create(Authentication authentication, @PathVariable String projectId,
                                                            @Valid @RequestBody ScenarioRequest request) {
        return ApiResponse.of(scenarios.create(principal(authentication), projectId, input(request, null)));
    }

    @GetMapping("/{scenarioId}")
    public ApiResponse<ApiAutomationScenarioDetail> detail(Authentication authentication, @PathVariable String projectId,
                                                            @PathVariable String scenarioId) {
        return ApiResponse.of(scenarios.detail(principal(authentication), projectId, scenarioId));
    }

    @PutMapping("/{scenarioId}")
    public ApiResponse<ApiAutomationScenarioDetail> update(Authentication authentication, @PathVariable String projectId,
                                                            @PathVariable String scenarioId, @Valid @RequestBody ScenarioUpdateRequest request) {
        return ApiResponse.of(scenarios.update(principal(authentication), projectId, scenarioId, input(request.scenario(), request.expectedVersion())));
    }

    @DeleteMapping("/{scenarioId}")
    public ResponseEntity<Void> delete(Authentication authentication, @PathVariable String projectId,
                                       @PathVariable String scenarioId) {
        scenarios.delete(principal(authentication), projectId, scenarioId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{scenarioId}/revisions")
    public ApiResponse<List<ApiAutomationScenarioRevision>> revisions(Authentication authentication, @PathVariable String projectId,
                                                                        @PathVariable String scenarioId) {
        return ApiResponse.of(scenarios.revisions(principal(authentication), projectId, scenarioId));
    }

    private ApiAutomationScenarioService.ScenarioInput input(ScenarioRequest request, Integer expectedVersion) {
        return new ApiAutomationScenarioService.ScenarioInput(request.name(), request.description(), request.environmentCode(),
                request.failureStrategy(), request.steps().stream().map(step -> new AutomationStep(step.id(), step.type(), step.name(), step.config())).toList(),
                request.variables(), request.preRequestScript(), request.postResponseScript(), expectedVersion);
    }

    private AuthenticatedPrincipal principal(Authentication authentication) {
        return (AuthenticatedPrincipal) authentication.getPrincipal();
    }

    public record ScenarioRequest(@NotBlank @Size(max = 160) String name, @Size(max = 1200) String description,
                                  @Size(max = 64) String environmentCode, @Size(max = 24) String failureStrategy,
                                  @NotEmpty @Size(max = 300) List<@Valid StepRequest> steps,
                                  @Size(max = 100) Map<@NotBlank @Size(max = 64) String, @Size(max = 10_000) String> variables,
                                  @Size(max = ApiAutomationScriptPolicy.MAX_SOURCE_LENGTH) String preRequestScript,
                                  @Size(max = ApiAutomationScriptPolicy.MAX_SOURCE_LENGTH) String postResponseScript) {
    }
    public record ScenarioUpdateRequest(@NotNull @Valid ScenarioRequest scenario, @Min(1) Integer expectedVersion) {
    }
    public record StepRequest(@NotBlank @Size(max = 64) String id, @NotBlank @Size(max = 24) String type,
                              @NotBlank @Size(max = 160) String name, @Size(max = 50) Map<String, Object> config) {
    }
}
