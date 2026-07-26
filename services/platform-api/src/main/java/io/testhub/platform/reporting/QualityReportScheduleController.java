package io.testhub.platform.reporting;

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
@RequestMapping("/api/v1/reports/quality/schedules")
public class QualityReportScheduleController {
    private final QualityReportScheduleService schedules;

    public QualityReportScheduleController(QualityReportScheduleService schedules) {
        this.schedules = schedules;
    }

    @GetMapping
    public ApiResponse<List<QualityReportSchedule>> list(Authentication authentication) {
        return ApiResponse.of(schedules.list(principal(authentication)));
    }

    @PostMapping
    public ApiResponse<QualityReportSchedule> create(Authentication authentication, @Valid @RequestBody ScheduleRequest request) {
        return ApiResponse.of(schedules.create(principal(authentication), input(request)));
    }

    @PutMapping("/{scheduleId}")
    public ApiResponse<QualityReportSchedule> update(Authentication authentication, @PathVariable String scheduleId,
                                                      @Valid @RequestBody ScheduleRequest request) {
        return ApiResponse.of(schedules.update(principal(authentication), scheduleId, input(request)));
    }

    @DeleteMapping("/{scheduleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication authentication, @PathVariable String scheduleId) {
        schedules.delete(principal(authentication), scheduleId);
    }

    @PostMapping("/{scheduleId}/run")
    public ApiResponse<QualityReportScheduleRun> run(Authentication authentication, @PathVariable String scheduleId) {
        return ApiResponse.of(schedules.runNow(principal(authentication), scheduleId));
    }

    @GetMapping("/{scheduleId}/runs")
    public ApiResponse<List<QualityReportScheduleRun>> runs(Authentication authentication, @PathVariable String scheduleId) {
        return ApiResponse.of(schedules.runs(principal(authentication), scheduleId));
    }

    private QualityReportScheduleService.ScheduleInput input(ScheduleRequest request) {
        return new QualityReportScheduleService.ScheduleInput(request.name(), request.projectIds(), request.cronExpression(),
                request.timezone(), request.periodDays(), request.enabled());
    }

    private AuthenticatedPrincipal principal(Authentication authentication) {
        return (AuthenticatedPrincipal) authentication.getPrincipal();
    }

    public record ScheduleRequest(@NotBlank @Size(max = 160) String name, @NotEmpty @Size(max = 50) List<@NotBlank @Size(max = 26) String> projectIds,
                                  @NotBlank @Size(max = 160) String cronExpression, @NotBlank @Size(max = 64) String timezone,
                                  @NotNull @Min(1) @Max(90) Integer periodDays, @NotNull Boolean enabled) { }
}
