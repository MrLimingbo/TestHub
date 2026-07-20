package io.testhub.platform.apiautomation;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
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
@RequestMapping("/api/v1/projects/{projectId}/api-automation/schedules")
public class ApiAutomationScheduleController {
    private final ApiAutomationScheduleService schedules;

    public ApiAutomationScheduleController(ApiAutomationScheduleService schedules) {
        this.schedules = schedules;
    }

    @GetMapping
    public ApiResponse<List<ApiAutomationSchedule>> list(Authentication authentication, @PathVariable String projectId) {
        return ApiResponse.of(schedules.list(principal(authentication), projectId));
    }

    @PostMapping
    public ApiResponse<ApiAutomationSchedule> create(Authentication authentication, @PathVariable String projectId,
                                                     @Valid @RequestBody ScheduleRequest request) {
        return ApiResponse.of(schedules.create(principal(authentication), projectId, input(request)));
    }

    @PutMapping("/{scheduleId}")
    public ApiResponse<ApiAutomationSchedule> update(Authentication authentication, @PathVariable String projectId,
                                                     @PathVariable String scheduleId, @Valid @RequestBody ScheduleRequest request) {
        return ApiResponse.of(schedules.update(principal(authentication), projectId, scheduleId, input(request)));
    }

    @DeleteMapping("/{scheduleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication authentication, @PathVariable String projectId, @PathVariable String scheduleId) {
        schedules.delete(principal(authentication), projectId, scheduleId);
    }

    @PostMapping("/{scheduleId}/run")
    public ApiResponse<ApiAutomationExecution> runNow(Authentication authentication, @PathVariable String projectId,
                                                       @PathVariable String scheduleId) {
        return ApiResponse.of(schedules.runNow(principal(authentication), projectId, scheduleId));
    }

    private ApiAutomationScheduleService.ScheduleInput input(ScheduleRequest request) {
        return new ApiAutomationScheduleService.ScheduleInput(request.scenarioId(), request.name(), request.cronExpression(),
                request.timezone(), request.timeoutSeconds(), request.maxConsecutiveFailures(), request.enabled());
    }

    private AuthenticatedPrincipal principal(Authentication authentication) {
        return (AuthenticatedPrincipal) authentication.getPrincipal();
    }

    public record ScheduleRequest(@NotBlank @Size(max = 26) String scenarioId, @NotBlank @Size(max = 160) String name,
                                  @NotBlank @Size(max = 160) String cronExpression, @NotBlank @Size(max = 64) String timezone,
                                  @NotNull @Min(10) @Max(3600) Integer timeoutSeconds,
                                  @NotNull @Min(1) @Max(10) Integer maxConsecutiveFailures,
                                  @NotNull Boolean enabled) {
    }
}
