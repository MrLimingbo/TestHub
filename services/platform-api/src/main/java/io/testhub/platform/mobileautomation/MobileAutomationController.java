package io.testhub.platform.mobileautomation;

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
@RequestMapping("/api/v1/projects/{projectId}/mobile-automation")
public class MobileAutomationController {
    private final MobileAutomationService mobile;
    public MobileAutomationController(MobileAutomationService mobile) { this.mobile = mobile; }

    @GetMapping
    public ApiResponse<MobileAutomationService.Snapshot> snapshot(Authentication authentication, @PathVariable String projectId) { return ApiResponse.of(mobile.snapshot(principal(authentication), projectId)); }
    @PostMapping("/devices")
    public ApiResponse<MobileAutomationService.Device> createDevice(Authentication authentication, @PathVariable String projectId, @Valid @RequestBody DeviceRequest request) { return ApiResponse.of(mobile.createDevice(principal(authentication), projectId, new MobileAutomationService.DeviceRequest(request.name(), request.platform(), request.udid(), request.automationName(), request.capabilities(), request.enabled()))); }
    @PutMapping("/devices/{id}")
    public ApiResponse<MobileAutomationService.Device> updateDevice(Authentication authentication, @PathVariable String projectId, @PathVariable String id, @Valid @RequestBody DeviceUpdate request) {
        var d = request.device(); return ApiResponse.of(mobile.updateDevice(principal(authentication),projectId,id,request.expectedVersion(),new MobileAutomationService.DeviceRequest(d.name(),d.platform(),d.udid(),d.automationName(),d.capabilities(),d.enabled())));
    }
    @PutMapping("/applications/{id}")
    public ApiResponse<MobileAutomationService.Application> updateApplication(Authentication authentication, @PathVariable String projectId, @PathVariable String id, @Valid @RequestBody ApplicationUpdate request) {
        var a = request.application(); return ApiResponse.of(mobile.updateApplication(principal(authentication),projectId,id,request.expectedVersion(),new MobileAutomationService.ApplicationRequest(a.name(),a.platform(),a.appIdentifier(),a.versionName(),a.appFileId(),a.enabled())));
    }
    @PutMapping("/elements/{id}")
    public ApiResponse<MobileAutomationService.Element> updateElement(Authentication authentication, @PathVariable String projectId, @PathVariable String id, @Valid @RequestBody ElementUpdate request) {
        var e = request.element(); return ApiResponse.of(mobile.updateElement(principal(authentication),projectId,id,request.expectedUpdatedAt(),new MobileAutomationService.ElementRequest(e.name(),e.platform(),e.locatorStrategy(),e.locatorValue(),e.description())));
    }
    @PutMapping("/scenarios/{id}")
    public ApiResponse<MobileAutomationService.Scenario> updateScenario(Authentication authentication, @PathVariable String projectId, @PathVariable String id, @Valid @RequestBody ScenarioUpdate request) {
        var s = request.scenario(); return ApiResponse.of(mobile.updateScenario(principal(authentication),projectId,id,request.expectedVersion(),new MobileAutomationService.ScenarioRequest(s.deviceId(),s.applicationId(),s.name(),s.description(),s.failureStrategy(),s.variables(),s.steps().stream().map(step -> new MobileAutomationService.Step(step.id(),step.type(),step.name(),step.elementId(),step.value(),step.expectedText(),step.timeoutMillis())).toList())));
    }
    @DeleteMapping("/devices/{deviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDevice(Authentication authentication, @PathVariable String projectId, @PathVariable String deviceId) { mobile.deleteDevice(principal(authentication), projectId, deviceId); }
    @PostMapping("/applications")
    public ApiResponse<MobileAutomationService.Application> createApplication(Authentication authentication, @PathVariable String projectId, @Valid @RequestBody ApplicationRequest request) { return ApiResponse.of(mobile.createApplication(principal(authentication), projectId, new MobileAutomationService.ApplicationRequest(request.name(), request.platform(), request.appIdentifier(), request.versionName(), request.appFileId(), request.enabled()))); }
    @DeleteMapping("/applications/{applicationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteApplication(Authentication authentication, @PathVariable String projectId, @PathVariable String applicationId) { mobile.deleteApplication(principal(authentication), projectId, applicationId); }
    @PostMapping("/elements")
    public ApiResponse<MobileAutomationService.Element> createElement(Authentication authentication, @PathVariable String projectId, @Valid @RequestBody ElementRequest request) { return ApiResponse.of(mobile.createElement(principal(authentication), projectId, new MobileAutomationService.ElementRequest(request.name(), request.platform(), request.locatorStrategy(), request.locatorValue(), request.description()))); }
    @DeleteMapping("/elements/{elementId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteElement(Authentication authentication, @PathVariable String projectId, @PathVariable String elementId) { mobile.deleteElement(principal(authentication), projectId, elementId); }
    @PostMapping("/scenarios")
    public ApiResponse<MobileAutomationService.Scenario> createScenario(Authentication authentication, @PathVariable String projectId, @Valid @RequestBody ScenarioRequest request) { return ApiResponse.of(mobile.createScenario(principal(authentication), projectId, new MobileAutomationService.ScenarioRequest(request.deviceId(), request.applicationId(), request.name(), request.description(), request.failureStrategy(), request.variables(), request.steps().stream().map(step -> new MobileAutomationService.Step(step.id(), step.type(), step.name(), step.elementId(), step.value(), step.expectedText(), step.timeoutMillis())).toList()))); }
    @DeleteMapping("/scenarios/{scenarioId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteScenario(Authentication authentication, @PathVariable String projectId, @PathVariable String scenarioId) { mobile.deleteScenario(principal(authentication), projectId, scenarioId); }
    @PostMapping("/scenarios/{scenarioId}/executions")
    public ApiResponse<MobileAutomationService.Execution> execute(Authentication authentication, @PathVariable String projectId, @PathVariable String scenarioId, @Valid @RequestBody(required = false) ExecuteRequest request) { return ApiResponse.of(mobile.execute(principal(authentication), projectId, scenarioId, request == null ? null : request.timeoutSeconds())); }
    @PostMapping("/executions/{executionId}/cancel")
    public ApiResponse<MobileAutomationService.Execution> cancel(Authentication authentication, @PathVariable String projectId, @PathVariable String executionId) { return ApiResponse.of(mobile.cancel(principal(authentication), projectId, executionId)); }
    @GetMapping("/executions/{executionId}/report")
    public ApiResponse<MobileAutomationService.Report> report(Authentication authentication, @PathVariable String projectId, @PathVariable String executionId) { return ApiResponse.of(mobile.report(principal(authentication), projectId, executionId)); }

    private AuthenticatedPrincipal principal(Authentication authentication) { return (AuthenticatedPrincipal) authentication.getPrincipal(); }
    public record DeviceRequest(@NotBlank @Size(max = 120) String name, @NotBlank @Size(max = 16) String platform, @NotBlank @Size(max = 255) String udid, @NotBlank @Size(max = 64) String automationName, Map<String, String> capabilities, @NotNull Boolean enabled) { }
    public record ApplicationRequest(@NotBlank @Size(max = 120) String name, @NotBlank @Size(max = 16) String platform, @NotBlank @Size(max = 255) String appIdentifier, @Size(max = 120) String versionName, @Size(max = 26) String appFileId, @NotNull Boolean enabled) { }
    public record ElementRequest(@NotBlank @Size(max = 120) String name, @NotBlank @Size(max = 16) String platform, @NotBlank @Size(max = 32) String locatorStrategy, @NotBlank @Size(max = 1024) String locatorValue, @Size(max = 1000) String description) { }
    public record ScenarioRequest(@NotBlank @Size(max = 26) String deviceId, @NotBlank @Size(max = 26) String applicationId, @NotBlank @Size(max = 160) String name, @Size(max = 2000) String description, @NotBlank @Size(max = 16) String failureStrategy, Map<String, String> variables, @NotEmpty @Size(max = 300) List<@NotNull @Valid StepRequest> steps) { }
    public record StepRequest(@NotBlank @Size(max = 64) String id, @NotBlank @Size(max = 32) String type, @NotBlank @Size(max = 160) String name, @Size(max = 26) String elementId, @Size(max = 4096) String value, @Size(max = 4096) String expectedText, @Min(1) @Max(60000) Integer timeoutMillis) { }
    public record DeviceUpdate(@Min(1) int expectedVersion, @NotNull @Valid DeviceRequest device) { }
    public record ApplicationUpdate(@Min(1) int expectedVersion, @NotNull @Valid ApplicationRequest application) { }
    public record ElementUpdate(@NotNull java.time.Instant expectedUpdatedAt, @NotNull @Valid ElementRequest element) { }
    public record ScenarioUpdate(@Min(1) int expectedVersion, @NotNull @Valid ScenarioRequest scenario) { }
    public record ExecuteRequest(@Min(10) @Max(3600) Integer timeoutSeconds) { }
}
