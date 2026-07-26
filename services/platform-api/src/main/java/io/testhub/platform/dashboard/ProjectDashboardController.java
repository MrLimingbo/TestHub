package io.testhub.platform.dashboard;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/dashboard")
public class ProjectDashboardController {
    private final ProjectDashboardService dashboard;

    public ProjectDashboardController(ProjectDashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping
    public ApiResponse<ProjectDashboard> get(Authentication authentication, @PathVariable String projectId,
                                             @RequestParam(defaultValue = "7") int days) {
        if (days < 7 || days > 90) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "统计周期应在 7 到 90 天之间");
        return ApiResponse.of(dashboard.get((AuthenticatedPrincipal) authentication.getPrincipal(), projectId, days));
    }
}
