package io.testhub.platform.version;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/versions/sync")
public class ProjectVersionSyncController {
    private final ProjectVersionSyncService sync;
    public ProjectVersionSyncController(ProjectVersionSyncService sync) {this.sync=sync;}
    @GetMapping public ApiResponse<ProjectVersionSyncService.Snapshot> snapshot(Authentication auth,@PathVariable String projectId,@RequestParam String expectedUserId) {
        return ApiResponse.of(sync.snapshot((AuthenticatedPrincipal)auth.getPrincipal(),projectId,expectedUserId));
    }
    @PostMapping public ApiResponse<ProjectVersionSyncService.Receipt> apply(Authentication auth,@PathVariable String projectId,@RequestBody byte[] body) {
        return ApiResponse.of(sync.apply((AuthenticatedPrincipal)auth.getPrincipal(),projectId,sync.decode(body)));
    }
}
