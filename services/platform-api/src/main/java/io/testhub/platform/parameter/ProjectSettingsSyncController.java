package io.testhub.platform.parameter;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/project-settings/sync")
public class ProjectSettingsSyncController {
    private final ProjectSettingsSyncService sync;
    public ProjectSettingsSyncController(ProjectSettingsSyncService sync) { this.sync = sync; }
    @GetMapping public ApiResponse<ProjectSettingsSyncService.Snapshot> snapshot(Authentication authentication, @PathVariable String projectId, @RequestParam String expectedUserId) {
        return ApiResponse.of(sync.snapshot((AuthenticatedPrincipal) authentication.getPrincipal(), projectId, expectedUserId));
    }
    @PostMapping public ApiResponse<ProjectSettingsSyncService.Receipt> apply(Authentication authentication, @PathVariable String projectId, @RequestBody byte[] body) {
        return ApiResponse.of(sync.apply((AuthenticatedPrincipal) authentication.getPrincipal(), projectId, sync.decode(body)));
    }
}
