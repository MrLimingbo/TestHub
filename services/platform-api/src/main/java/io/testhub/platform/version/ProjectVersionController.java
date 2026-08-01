package io.testhub.platform.version;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
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
@RequestMapping("/api/v1/projects/{projectId}/versions")
public class ProjectVersionController {
    private final ProjectVersionService versions;

    public ProjectVersionController(ProjectVersionService versions) {
        this.versions = versions;
    }

    @GetMapping
    public ApiResponse<List<ProjectVersion>> list(Authentication authentication, @PathVariable String projectId) {
        return ApiResponse.of(versions.list(principal(authentication), projectId));
    }

    @PostMapping
    public ApiResponse<ProjectVersion> create(Authentication authentication, @PathVariable String projectId,
                                              @Valid @RequestBody VersionRequest request) {
        return ApiResponse.of(versions.create(principal(authentication), projectId, request.toInput()));
    }

    @PutMapping("/{versionId}")
    public ApiResponse<ProjectVersion> update(Authentication authentication, @PathVariable String projectId,
                                              @PathVariable String versionId,
                                              @Valid @RequestBody VersionRequest request) {
        return ApiResponse.of(versions.update(principal(authentication), projectId, versionId, request.toInput()));
    }

    @PutMapping("/{versionId}/status")
    public ApiResponse<ProjectVersion> updateStatus(Authentication authentication, @PathVariable String projectId,
                                                    @PathVariable String versionId,
                                                    @Valid @RequestBody StatusRequest request) {
        return ApiResponse.of(versions.updateStatus(principal(authentication), projectId, versionId,
                new ProjectVersionService.StatusInput(request.status(), request.actualReleaseDate())));
    }

    @DeleteMapping("/{versionId}")
    public ResponseEntity<Void> delete(Authentication authentication, @PathVariable String projectId,
                                       @PathVariable String versionId) {
        versions.delete(principal(authentication), projectId, versionId);
        return ResponseEntity.noContent().build();
    }

    private AuthenticatedPrincipal principal(Authentication authentication) {
        return (AuthenticatedPrincipal) authentication.getPrincipal();
    }

    public record VersionRequest(@NotBlank @Size(max = 100) String versionNumber,
                                 @Size(max = 50) List<@NotBlank @Size(max = 100) String> buildNumbers,
                                 String status, LocalDate plannedReleaseDate, LocalDate actualReleaseDate,
                                 @Size(max = 20_000) String description) {
        ProjectVersionService.VersionInput toInput() {
            return new ProjectVersionService.VersionInput(versionNumber, buildNumbers, status,
                    plannedReleaseDate, actualReleaseDate, description);
        }
    }

    public record StatusRequest(@NotBlank String status, LocalDate actualReleaseDate) { }
}
