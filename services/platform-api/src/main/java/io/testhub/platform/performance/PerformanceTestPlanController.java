package io.testhub.platform.performance;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
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
@RequestMapping("/api/v1/projects/{projectId}/performance/plans")
public class PerformanceTestPlanController {
    private final PerformanceTestPlanService plans;

    public PerformanceTestPlanController(PerformanceTestPlanService plans) {
        this.plans = plans;
    }

    @GetMapping
    public ApiResponse<List<PerformanceTestPlan>> list(Authentication authentication, @PathVariable String projectId) {
        return ApiResponse.of(plans.list(principal(authentication), projectId));
    }

    @GetMapping("/{planId}")
    public ApiResponse<PerformanceTestPlanDetail> detail(Authentication authentication, @PathVariable String projectId,
                                                          @PathVariable String planId) {
        return ApiResponse.of(plans.detail(principal(authentication), projectId, planId));
    }

    @PostMapping
    public ApiResponse<PerformanceTestPlanDetail> create(Authentication authentication, @PathVariable String projectId,
                                                          @Valid @RequestBody PlanRequest request) {
        return ApiResponse.of(plans.create(principal(authentication), projectId, request.toInput(null)));
    }

    @PutMapping("/{planId}")
    public ApiResponse<PerformanceTestPlanDetail> update(Authentication authentication, @PathVariable String projectId,
                                                          @PathVariable String planId,
                                                          @Valid @RequestBody PlanUpdateRequest request) {
        return ApiResponse.of(plans.update(principal(authentication), projectId, planId,
                request.plan().toInput(request.expectedVersion())));
    }

    @DeleteMapping("/{planId}")
    public ResponseEntity<Void> delete(Authentication authentication, @PathVariable String projectId,
                                       @PathVariable String planId) {
        plans.delete(principal(authentication), projectId, planId);
        return ResponseEntity.noContent().build();
    }

    private AuthenticatedPrincipal principal(Authentication authentication) {
        return (AuthenticatedPrincipal) authentication.getPrincipal();
    }

    public record PlanRequest(@NotBlank @Size(max = 160) String name, @Size(max = 2_000) String description,
                              @Size(max = 24) String status, @NotEmpty @Size(max = 10)
                              List<@NotNull @Valid ThreadGroupRequest> threadGroups) {
        PerformanceTestPlanService.PlanInput toInput(Integer expectedVersion) {
            return new PerformanceTestPlanService.PlanInput(name, description, status,
                    threadGroups.stream().map(ThreadGroupRequest::toInput).toList(), expectedVersion);
        }
    }

    public record PlanUpdateRequest(@NotNull @Min(1) Integer expectedVersion, @NotNull @Valid PlanRequest plan) {
    }

    public record ThreadGroupRequest(@NotBlank @Size(max = 160) String name, @NotBlank @Size(max = 2_048) String targetUrl,
                                     @Size(max = 24) String loadModel, @NotNull @Min(1) Integer threadCount,
                                     @NotNull @Min(0) Integer rampUpSeconds, @Min(2) @Max(20) Integer stepCount,
                                     @Min(1) @Max(86_400) Integer stepIntervalSeconds, @NotNull @Min(10) Integer durationSeconds,
                                     @Min(1) Integer targetTps, @Min(1) Integer p95ThresholdMillis,
                                     @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal errorThresholdPercent,
                                     @Size(max = 10) String requestMethod,
                                     @Size(max = 16) Map<@NotBlank @Size(max = 128) String, @Size(max = 1_024) String> requestHeaders,
                                     @Size(max = 16) Map<@NotBlank @Size(max = 128) String, @Size(max = 1_024) String> queryParameters,
                                     @Size(max = 65_536) String requestBody, @Min(100) @Max(599) Integer expectedStatusCode,
                                     @Size(max = 1_000) String responseContains, @Size(max = 65_536) String dataSetCsv,
                                     @Min(2) Integer rendezvousUsers) {
        PerformanceTestPlanService.ThreadGroupInput toInput() {
            return new PerformanceTestPlanService.ThreadGroupInput(name, targetUrl, loadModel, threadCount, rampUpSeconds,
                    stepCount, stepIntervalSeconds, durationSeconds, targetTps, p95ThresholdMillis, errorThresholdPercent, requestMethod, requestHeaders,
                    queryParameters, requestBody, expectedStatusCode, responseContains, dataSetCsv, rendezvousUsers);
        }
    }
}
