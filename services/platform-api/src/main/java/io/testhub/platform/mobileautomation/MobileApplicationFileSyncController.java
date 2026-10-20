package io.testhub.platform.mobileautomation;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/mobile-automation/application-files/sync")
public class MobileApplicationFileSyncController {
    private final MobileApplicationFileSyncService sync;
    public MobileApplicationFileSyncController(MobileApplicationFileSyncService sync){this.sync=sync;}
    @GetMapping public ApiResponse<MobileApplicationFileSyncService.Snapshot> snapshot(Authentication auth,@PathVariable String projectId,@RequestParam String expectedUserId){return ApiResponse.of(sync.snapshot(actor(auth),projectId,expectedUserId));}
    @PostMapping public ApiResponse<MobileApplicationFileSyncService.Receipt> apply(Authentication auth,@PathVariable String projectId,@RequestBody byte[] body){return ApiResponse.of(sync.apply(actor(auth),projectId,sync.decode(body)));}
    private AuthenticatedPrincipal actor(Authentication auth){return (AuthenticatedPrincipal)auth.getPrincipal();}
}
