package io.testhub.platform.execution;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/tasks/executions")
public class UnifiedExecutionController {
    private final UnifiedExecutionService executions;
    public UnifiedExecutionController(UnifiedExecutionService executions) { this.executions = executions; }

    @GetMapping
    public ApiResponse<List<UnifiedExecution>> list(Authentication authentication, @PathVariable String projectId) {
        return ApiResponse.of(executions.list((AuthenticatedPrincipal) authentication.getPrincipal(), projectId));
    }
}
