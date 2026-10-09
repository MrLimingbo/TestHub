package io.testhub.platform.uiautomation;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/ui-automation/sync")
public class UiAutomationSyncController {
    private final UiAutomationSyncService sync;
    public UiAutomationSyncController(UiAutomationSyncService sync) {this.sync=sync;}
    @GetMapping public ApiResponse<UiAutomationSyncService.Snapshot> snapshot(Authentication auth,@PathVariable String projectId,@RequestParam String expectedUserId) {
        return ApiResponse.of(sync.snapshot((AuthenticatedPrincipal)auth.getPrincipal(),projectId,expectedUserId));
    }
    @PostMapping public ApiResponse<UiAutomationSyncService.Receipt> apply(Authentication auth,@PathVariable String projectId,@RequestBody byte[] body) {
        return ApiResponse.of(sync.apply((AuthenticatedPrincipal)auth.getPrincipal(),projectId,sync.decode(body)));
    }
}
