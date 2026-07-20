package io.testhub.platform.performance;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/performance")
public class PerformanceTestExecutionController {
    private final PerformanceTestExecutionService executions;

    public PerformanceTestExecutionController(PerformanceTestExecutionService executions) {
        this.executions = executions;
    }

    @GetMapping("/executions")
    public ApiResponse<List<PerformanceTestExecution>> list(Authentication authentication, @PathVariable String projectId) {
        return ApiResponse.of(executions.list(principal(authentication), projectId));
    }

    @PostMapping("/plans/{planId}/executions")
    public ApiResponse<PerformanceTestExecution> create(Authentication authentication, @PathVariable String projectId,
                                                         @PathVariable String planId,
                                                         @Valid @RequestBody ExecutionRequest request) {
        return ApiResponse.of(executions.create(principal(authentication), projectId, planId, request.timeoutSeconds()));
    }

    @GetMapping("/executions/{executionId}")
    public ApiResponse<PerformanceTestExecution> detail(Authentication authentication, @PathVariable String projectId,
                                                         @PathVariable String executionId) {
        return ApiResponse.of(executions.detail(principal(authentication), projectId, executionId));
    }

    @GetMapping("/executions/{executionId}/logs")
    public ApiResponse<List<PerformanceTestExecutionLog>> logs(Authentication authentication, @PathVariable String projectId,
                                                                @PathVariable String executionId) {
        return ApiResponse.of(executions.logs(principal(authentication), projectId, executionId));
    }

    @GetMapping("/executions/{executionId}/metrics")
    public ApiResponse<List<PerformanceTestExecutionMetric>> metrics(Authentication authentication, @PathVariable String projectId,
                                                                       @PathVariable String executionId) {
        return ApiResponse.of(executions.metrics(principal(authentication), projectId, executionId));
    }

    @GetMapping("/executions/{executionId}/resources")
    public ApiResponse<List<PerformanceTestExecutionResourceMetric>> resources(Authentication authentication, @PathVariable String projectId,
                                                                                @PathVariable String executionId) {
        return ApiResponse.of(executions.resources(principal(authentication), projectId, executionId));
    }

    @GetMapping("/executions/{executionId}/report")
    public ApiResponse<PerformanceTestExecutionReport> report(Authentication authentication, @PathVariable String projectId,
                                                               @PathVariable String executionId) {
        return ApiResponse.of(executions.report(principal(authentication), projectId, executionId));
    }

    @GetMapping("/executions/{executionId}/report/export")
    public ResponseEntity<ByteArrayResource> exportReport(Authentication authentication, @PathVariable String projectId,
                                                           @PathVariable String executionId) {
        PerformanceTestExecutionService.CsvExport export = executions.exportReport(principal(authentication), projectId, executionId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv;charset=UTF-8"));
        headers.setContentLength(export.content().length);
        headers.setContentDisposition(ContentDisposition.attachment().filename(export.filename()).build());
        return ResponseEntity.ok().headers(headers).body(new ByteArrayResource(export.content()));
    }

    @DeleteMapping("/executions/{executionId}")
    public ApiResponse<PerformanceTestExecution> cancel(Authentication authentication, @PathVariable String projectId,
                                                         @PathVariable String executionId) {
        return ApiResponse.of(executions.cancel(principal(authentication), projectId, executionId));
    }

    private AuthenticatedPrincipal principal(Authentication authentication) {
        return (AuthenticatedPrincipal) authentication.getPrincipal();
    }

    public record ExecutionRequest(@Min(60) @Max(86_400) Integer timeoutSeconds) {
    }
}
