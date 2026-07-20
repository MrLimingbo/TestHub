package io.testhub.platform.apiautomation;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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
@RequestMapping("/api/v1/projects/{projectId}/api-automation/execution-profiles")
public class ApiAutomationExecutionProfileController {
    private final ApiAutomationExecutionProfileService profiles;
    public ApiAutomationExecutionProfileController(ApiAutomationExecutionProfileService profiles) { this.profiles = profiles; }
    @GetMapping public ApiResponse<List<ApiAutomationExecutionProfile>> list(Authentication authentication, @PathVariable String projectId) { return ApiResponse.of(profiles.list(principal(authentication), projectId)); }
    @GetMapping("/{profileId}") public ApiResponse<ApiAutomationExecutionProfileDetail> detail(Authentication authentication, @PathVariable String projectId, @PathVariable String profileId) { return ApiResponse.of(profiles.detail(principal(authentication), projectId, profileId)); }
    @PostMapping public ApiResponse<ApiAutomationExecutionProfileDetail> create(Authentication authentication, @PathVariable String projectId, @Valid @RequestBody ProfileRequest request) { return ApiResponse.of(profiles.create(principal(authentication), projectId, input(request, null))); }
    @PutMapping("/{profileId}") public ApiResponse<ApiAutomationExecutionProfileDetail> update(Authentication authentication, @PathVariable String projectId, @PathVariable String profileId, @Valid @RequestBody ProfileUpdateRequest request) { return ApiResponse.of(profiles.update(principal(authentication), projectId, profileId, input(request.profile(), request.expectedVersion()))); }
    private ApiAutomationExecutionProfileService.ProfileInput input(ProfileRequest request, Integer version) { return new ApiAutomationExecutionProfileService.ProfileInput(request.name(), request.environmentCode(), request.variables(), version); }
    private AuthenticatedPrincipal principal(Authentication authentication) { return (AuthenticatedPrincipal) authentication.getPrincipal(); }
    public record ProfileRequest(@NotBlank @Size(max = 160) String name, @NotBlank @Size(max = 64) String environmentCode, @Size(max = 100) Map<@NotBlank @Size(max = 64) String, @Size(max = 10_000) String> variables) { }
    public record ProfileUpdateRequest(@NotNull @Valid ProfileRequest profile, @Min(1) Integer expectedVersion) { }
}
