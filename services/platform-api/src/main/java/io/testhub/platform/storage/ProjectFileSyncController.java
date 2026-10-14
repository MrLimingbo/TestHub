package io.testhub.platform.storage;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/files/sync")
public class ProjectFileSyncController {
    private final ProjectFileSyncService sync;
    public ProjectFileSyncController(ProjectFileSyncService sync) { this.sync = sync; }

    @GetMapping
    public ApiResponse<ProjectFileSyncService.Snapshot> snapshot(Authentication authentication,
            @PathVariable String projectId, @RequestParam String expectedUserId) {
        return ApiResponse.of(sync.snapshot((AuthenticatedPrincipal) authentication.getPrincipal(), projectId, expectedUserId));
    }

    @PostMapping
    public ApiResponse<ProjectFileSyncService.Receipt> apply(Authentication authentication,
            @PathVariable String projectId, @RequestBody byte[] body) {
        return ApiResponse.of(sync.apply((AuthenticatedPrincipal) authentication.getPrincipal(), projectId, sync.decode(body)));
    }
}
