package io.testhub.platform.reporting;
import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/projects/{projectId}/quality-report/sync")
public class QualityReportSyncController {
    private final QualityReportSyncService sync;
    public QualityReportSyncController(QualityReportSyncService sync){this.sync=sync;}
    @GetMapping public ApiResponse<QualityReportSyncService.Snapshot> snapshot(Authentication a,@PathVariable String projectId,@RequestParam String expectedUserId){return ApiResponse.of(sync.snapshot((AuthenticatedPrincipal)a.getPrincipal(),projectId,expectedUserId));}
    @PostMapping public ApiResponse<QualityReportSyncService.Receipt> apply(Authentication a,@PathVariable String projectId,@RequestBody byte[] body){return ApiResponse.of(sync.apply((AuthenticatedPrincipal)a.getPrincipal(),projectId,sync.decode(body)));}
}
