package io.testhub.platform.performance;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/performance/sync")
public class PerformanceSyncController {
    private final PerformanceSyncService sync;
    public PerformanceSyncController(PerformanceSyncService sync) { this.sync = sync; }
    @GetMapping public ApiResponse<PerformanceSyncService.Snapshot> snapshot(Authentication auth, @PathVariable String projectId, @RequestParam String expectedUserId) {
        return ApiResponse.of(sync.snapshot((AuthenticatedPrincipal) auth.getPrincipal(), projectId, expectedUserId));
    }
    @PostMapping public ApiResponse<PerformanceSyncService.Receipt> apply(Authentication auth, @PathVariable String projectId, @RequestBody byte[] body) {
        return ApiResponse.of(sync.apply((AuthenticatedPrincipal) auth.getPrincipal(), projectId, sync.decode(body)));
    }
}
