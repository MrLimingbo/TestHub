package io.testhub.platform.parameter;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
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
@RequestMapping("/api/v1/projects/{projectId}/environments")
public class ProjectEnvironmentController {
    private final ProjectEnvironmentService environments;

    public ProjectEnvironmentController(ProjectEnvironmentService environments) {
        this.environments = environments;
    }

    @GetMapping
    public ApiResponse<List<ProjectEnvironment>> list(Authentication authentication, @PathVariable String projectId) {
        return ApiResponse.of(environments.list(principal(authentication), projectId));
    }

    @PostMapping
    public ApiResponse<ProjectEnvironment> create(Authentication authentication, @PathVariable String projectId,
                                                  @Valid @RequestBody EnvironmentCreateRequest request) {
        return ApiResponse.of(environments.create(principal(authentication), projectId,
                new ProjectEnvironmentService.CreateInput(request.name(), request.environmentCode(), request.baseUrl(),
                        request.description(), request.enabled(), request.defaultEnvironment())));
    }

    @PutMapping("/{environmentId}")
    public ApiResponse<ProjectEnvironment> update(Authentication authentication, @PathVariable String projectId,
                                                  @PathVariable String environmentId,
                                                  @Valid @RequestBody EnvironmentUpdateRequest request) {
        return ApiResponse.of(environments.update(principal(authentication), projectId, environmentId,
                new ProjectEnvironmentService.UpdateInput(request.name(), request.baseUrl(), request.description(),
                        request.enabled(), request.expectedVersion())));
    }

    @PostMapping("/{environmentId}/default")
    public ApiResponse<ProjectEnvironment> makeDefault(Authentication authentication, @PathVariable String projectId,
                                                       @PathVariable String environmentId) {
        return ApiResponse.of(environments.makeDefault(principal(authentication), projectId, environmentId));
    }

    @DeleteMapping("/{environmentId}")
    public ResponseEntity<Void> delete(Authentication authentication, @PathVariable String projectId,
                                       @PathVariable String environmentId) {
        environments.delete(principal(authentication), projectId, environmentId);
        return ResponseEntity.noContent().build();
    }

    private AuthenticatedPrincipal principal(Authentication authentication) {
        return (AuthenticatedPrincipal) authentication.getPrincipal();
    }

    public record EnvironmentCreateRequest(
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Size(max = 64) String environmentCode,
            @Size(max = 2000) String baseUrl,
            @Size(max = 1200) String description,
            boolean enabled,
            boolean defaultEnvironment) {
    }

    public record EnvironmentUpdateRequest(
            @NotBlank @Size(max = 160) String name,
            @Size(max = 2000) String baseUrl,
            @Size(max = 1200) String description,
            boolean enabled,
            @Min(1) @Max(Integer.MAX_VALUE) int expectedVersion) {
    }
}
