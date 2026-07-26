package io.testhub.platform.mobileautomation;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/mobile-automation/runners")
public class MobileAutomationRunnerStatusController {
    private final MobileAutomationRunnerStatusService runners;
    public MobileAutomationRunnerStatusController(MobileAutomationRunnerStatusService runners) { this.runners = runners; }
    @GetMapping
    public ApiResponse<List<MobileAutomationRunnerStatus>> list(Authentication authentication, @PathVariable String projectId) {
        return ApiResponse.of(runners.list((AuthenticatedPrincipal) authentication.getPrincipal(), projectId));
    }
}
