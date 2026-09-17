package io.testhub.platform.uiautomation;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/ui-automation")
public class UiAutomationController {
    private final UiAutomationService automation;

    public UiAutomationController(UiAutomationService automation) {
        this.automation = automation;
    }

    @GetMapping("/browser-profiles")
    public ApiResponse<List<UiAutomationBrowserProfile>> profiles(Authentication authentication, @PathVariable String projectId) {
        return ApiResponse.of(automation.profiles(principal(authentication), projectId));
    }
    @PostMapping("/browser-profiles")
    public ApiResponse<UiAutomationBrowserProfile> createProfile(Authentication authentication, @PathVariable String projectId,
                                                                  @Valid @RequestBody ProfileRequest request) {
        return ApiResponse.of(automation.createProfile(principal(authentication), projectId, profile(request)));
    }
    @PutMapping("/browser-profiles/{profileId}")
    public ApiResponse<UiAutomationBrowserProfile> updateProfile(Authentication authentication, @PathVariable String projectId,
                                                                  @PathVariable String profileId, @Valid @RequestBody ProfileRequest request) {
        return ApiResponse.of(automation.updateProfile(principal(authentication), projectId, profileId, profile(request)));
    }
    @DeleteMapping("/browser-profiles/{profileId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProfile(Authentication authentication, @PathVariable String projectId, @PathVariable String profileId) {
        automation.deleteProfile(principal(authentication), projectId, profileId);
    }

    @GetMapping("/page-objects")
    public ApiResponse<List<UiAutomationPageObject>> pageObjects(Authentication authentication, @PathVariable String projectId) {
        return ApiResponse.of(automation.pageObjects(principal(authentication), projectId));
    }
    @PostMapping("/page-objects")
    public ApiResponse<UiAutomationPageObject> createPageObject(Authentication authentication, @PathVariable String projectId,
                                                                 @Valid @RequestBody PageObjectRequest request) {
        return ApiResponse.of(automation.createPageObject(principal(authentication), projectId, pageObject(request)));
    }
    @PutMapping("/page-objects/{pageObjectId}")
    public ApiResponse<UiAutomationPageObject> updatePageObject(Authentication authentication, @PathVariable String projectId,
                                                                 @PathVariable String pageObjectId, @Valid @RequestBody PageObjectRequest request) {
        return ApiResponse.of(automation.updatePageObject(principal(authentication), projectId, pageObjectId, pageObject(request)));
    }
    @DeleteMapping("/page-objects/{pageObjectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePageObject(Authentication authentication, @PathVariable String projectId, @PathVariable String pageObjectId) {
        automation.deletePageObject(principal(authentication), projectId, pageObjectId);
    }

    @GetMapping("/scenarios")
    public ApiResponse<List<UiAutomationScenario>> scenarios(Authentication authentication, @PathVariable String projectId) {
        return ApiResponse.of(automation.scenarios(principal(authentication), projectId));
    }
    @PostMapping("/scenarios")
    public ApiResponse<UiAutomationScenario> createScenario(Authentication authentication, @PathVariable String projectId,
                                                             @Valid @RequestBody ScenarioRequest request) {
        return ApiResponse.of(automation.createScenario(principal(authentication), projectId, scenario(request)));
    }
    @PutMapping("/scenarios/{scenarioId}")
    public ApiResponse<UiAutomationScenario> updateScenario(Authentication authentication, @PathVariable String projectId,
                                                             @PathVariable String scenarioId, @Valid @RequestBody ScenarioRequest request) {
        return ApiResponse.of(automation.updateScenario(principal(authentication), projectId, scenarioId, scenario(request)));
    }
    @DeleteMapping("/scenarios/{scenarioId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteScenario(Authentication authentication, @PathVariable String projectId, @PathVariable String scenarioId) {
        automation.deleteScenario(principal(authentication), projectId, scenarioId);
    }

    @GetMapping("/executions")
    public ApiResponse<List<UiAutomationExecution>> executions(Authentication authentication, @PathVariable String projectId) {
        return ApiResponse.of(automation.executions(principal(authentication), projectId));
    }
    @PostMapping("/scenarios/{scenarioId}/executions")
    public ApiResponse<UiAutomationExecution> execute(Authentication authentication, @PathVariable String projectId,
                                                        @PathVariable String scenarioId, @Valid @RequestBody(required = false) ExecuteRequest request) {
        return ApiResponse.of(automation.execute(principal(authentication), projectId, scenarioId, request == null ? null : request.timeoutSeconds(),
                request == null ? null : request.functionalPlanId()));
    }
    @PostMapping("/executions/{executionId}/retry")
    public ApiResponse<UiAutomationExecution> retry(Authentication authentication, @PathVariable String projectId, @PathVariable String executionId) {
        return ApiResponse.of(automation.retry(principal(authentication), projectId, executionId));
    }
    @PostMapping("/executions/{executionId}/cancel")
    public ApiResponse<UiAutomationExecution> cancel(Authentication authentication, @PathVariable String projectId, @PathVariable String executionId) {
        return ApiResponse.of(automation.cancel(principal(authentication), projectId, executionId));
    }
    @GetMapping("/executions/{executionId}/report")
    public ApiResponse<UiAutomationExecutionReport> report(Authentication authentication, @PathVariable String projectId,
                                                            @PathVariable String executionId) {
        return ApiResponse.of(automation.report(principal(authentication), projectId, executionId));
    }

    private UiAutomationService.ProfileRequest profile(ProfileRequest request) {
        return new UiAutomationService.ProfileRequest(request.name(), request.browser(), request.baseUrl(), request.viewportWidth(),
                request.viewportHeight(), request.enabled());
    }
    private UiAutomationService.PageObjectRequest pageObject(PageObjectRequest request) {
        return new UiAutomationService.PageObjectRequest(request.name(), request.selectorValue(), request.description());
    }
    private UiAutomationService.ScenarioRequest scenario(ScenarioRequest request) {
        List<UiAutomationStep> steps = request.steps().stream().map(step -> new UiAutomationStep(step.id(), step.type(), step.name(),
                step.selector(), step.pageObjectId(), step.value(), step.url(), step.expectedText(), step.timeoutMillis())).toList();
        return new UiAutomationService.ScenarioRequest(request.browserProfileId(), request.name(), request.description(),
                request.failureStrategy(), request.variables(), steps);
    }
    private AuthenticatedPrincipal principal(Authentication authentication) { return (AuthenticatedPrincipal) authentication.getPrincipal(); }

    public record ProfileRequest(@NotBlank @Size(max = 120) String name, @NotBlank @Size(max = 16) String browser,
                                 @NotBlank @Size(max = 2048) String baseUrl, @Min(320) @Max(3840) Integer viewportWidth,
                                 @Min(320) @Max(2160) Integer viewportHeight, @NotNull Boolean enabled) { }
    public record PageObjectRequest(@NotBlank @Size(max = 120) String name, @NotBlank @Size(max = 1024) String selectorValue,
                                    @Size(max = 1000) String description) { }
    public record ScenarioRequest(@NotBlank @Size(max = 26) String browserProfileId, @NotBlank @Size(max = 160) String name,
                                  @Size(max = 2000) String description, @NotBlank @Size(max = 16) String failureStrategy,
                                  Map<String, String> variables, @NotEmpty @Size(max = 300) List<@NotNull @Valid StepRequest> steps) { }
    public record StepRequest(@NotBlank @Size(max = 64) String id, @NotBlank @Size(max = 32) String type,
                              @NotBlank @Size(max = 160) String name, @Size(max = 1024) String selector,
                              @Size(max = 26) String pageObjectId, @Size(max = 4096) String value, @Size(max = 2048) String url,
                              @Size(max = 4096) String expectedText, @Min(1) @Max(60000) Integer timeoutMillis) { }
    public record ExecuteRequest(@Min(10) @Max(3600) Integer timeoutSeconds, @Size(max = 26) String functionalPlanId) { }
}
