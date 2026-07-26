package io.testhub.platform.apiautomation;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/api-automation")
public class ApiAutomationExecutionController {
    private final ApiAutomationExecutionService executions;
    private final ApiAutomationExecutionReportArtifactService reportArtifacts;

    public ApiAutomationExecutionController(ApiAutomationExecutionService executions,
                                            ApiAutomationExecutionReportArtifactService reportArtifacts) {
        this.executions = executions;
        this.reportArtifacts = reportArtifacts;
    }

    @GetMapping("/executions")
    public ApiResponse<List<ApiAutomationExecution>> list(Authentication authentication, @PathVariable String projectId) {
        return ApiResponse.of(executions.list(principal(authentication), projectId));
    }

    @GetMapping("/executions/{executionId}")
    public ApiResponse<ApiAutomationExecution> detail(Authentication authentication, @PathVariable String projectId,
                                                       @PathVariable String executionId) {
        return ApiResponse.of(executions.detail(principal(authentication), projectId, executionId));
    }

    @GetMapping("/executions/{executionId}/logs")
    public ApiResponse<List<ApiAutomationExecutionLog>> logs(Authentication authentication, @PathVariable String projectId,
                                                              @PathVariable String executionId) {
        return ApiResponse.of(executions.logs(principal(authentication), projectId, executionId));
    }

    @GetMapping("/executions/{executionId}/http-results")
    public ApiResponse<List<ApiAutomationExecutionHttpResult>> httpResults(Authentication authentication,
                                                                             @PathVariable String projectId,
                                                                             @PathVariable String executionId) {
        return ApiResponse.of(executions.httpResults(principal(authentication), projectId, executionId));
    }

    @GetMapping("/executions/{executionId}/step-results")
    public ApiResponse<List<ApiAutomationExecutionStepResult>> stepResults(Authentication authentication,
                                                                             @PathVariable String projectId,
                                                                             @PathVariable String executionId) {
        return ApiResponse.of(executions.stepResults(principal(authentication), projectId, executionId));
    }

    @GetMapping("/executions/{executionId}/script-results")
    public ApiResponse<List<ApiAutomationExecutionScriptResult>> scriptResults(Authentication authentication,
                                                                                @PathVariable String projectId,
                                                                                @PathVariable String executionId) {
        return ApiResponse.of(executions.scriptResults(principal(authentication), projectId, executionId));
    }

    @GetMapping("/executions/{executionId}/manifest")
    public ApiResponse<ApiAutomationExecutionManifest> manifest(Authentication authentication, @PathVariable String projectId,
                                                                 @PathVariable String executionId) {
        return ApiResponse.of(executions.manifest(principal(authentication), projectId, executionId));
    }

    @GetMapping("/executions/{executionId}/runtime-snapshot")
    public ApiResponse<ApiAutomationExecutionRuntimeSnapshotSummary> runtimeSnapshot(Authentication authentication,
                                                                                      @PathVariable String projectId,
                                                                                      @PathVariable String executionId) {
        return ApiResponse.of(executions.runtimeSnapshot(principal(authentication), projectId, executionId));
    }

    @GetMapping("/executions/{executionId}/report")
    public ApiResponse<ApiAutomationExecutionReport> report(Authentication authentication, @PathVariable String projectId,
                                                             @PathVariable String executionId) {
        return ApiResponse.of(executions.report(principal(authentication), projectId, executionId));
    }

    @GetMapping("/executions/{executionId}/report/integrity")
    public ApiResponse<ApiAutomationExecutionReportArtifactService.Metadata> reportIntegrity(Authentication authentication,
                                                                                               @PathVariable String projectId,
                                                                                               @PathVariable String executionId) {
        return ApiResponse.of(reportArtifacts.artifact(principal(authentication), projectId, executionId).metadata());
    }

    /** Raw attachment is deliberately the same value-bearing-safe report contract as the envelope endpoint. */
    @GetMapping(value = "/executions/{executionId}/report/export", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> exportReport(Authentication authentication, @PathVariable String projectId,
                                               @PathVariable String executionId,
                                               @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        ApiAutomationExecutionReportArtifactService.Artifact artifact = reportArtifacts.artifact(
                principal(authentication), projectId, executionId);
        ApiAutomationExecutionReportArtifactService.Metadata metadata = artifact.metadata();
        if (reportArtifacts.matchesIfNoneMatch(ifNoneMatch, metadata)) {
            return ResponseEntity.status(304).eTag(metadata.eTag()).header(HttpHeaders.CACHE_CONTROL, "private, no-cache")
                    .header(ApiAutomationExecutionReportArtifactService.SHA256_HEADER, metadata.sha256()).build();
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).eTag(metadata.eTag())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-cache")
                .header(ApiAutomationExecutionReportArtifactService.SHA256_HEADER, metadata.sha256())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + metadata.filename() + "\"")
                .body(artifact.content());
    }

    @PostMapping("/scenarios/{scenarioId}/executions")
    public ApiResponse<ApiAutomationExecution> create(Authentication authentication, @PathVariable String projectId,
                                                       @PathVariable String scenarioId, @Valid @RequestBody ExecutionRequest request) {
        return ApiResponse.of(executions.create(principal(authentication), projectId, scenarioId, request.timeoutSeconds(),
                request.executionProfileId(), request.overrides()));
    }

    @PostMapping("/scenarios/{scenarioId}/execution-configuration-preview")
    public ApiResponse<ApiAutomationExecutionConfigurationPreview> previewConfiguration(Authentication authentication,
                                                                                         @PathVariable String projectId,
                                                                                         @PathVariable String scenarioId,
                                                                                         @Valid @RequestBody ExecutionPreviewRequest request) {
        return ApiResponse.of(executions.previewConfiguration(principal(authentication), projectId, scenarioId,
                request.executionProfileId(), request.overrides()));
    }

    @DeleteMapping("/executions/{executionId}")
    public ApiResponse<ApiAutomationExecution> cancel(Authentication authentication, @PathVariable String projectId,
                                                       @PathVariable String executionId) {
        return ApiResponse.of(executions.cancel(principal(authentication), projectId, executionId));
    }

    private AuthenticatedPrincipal principal(Authentication authentication) {
        return (AuthenticatedPrincipal) authentication.getPrincipal();
    }

    public record ExecutionRequest(@Min(10) @Max(3600) Integer timeoutSeconds, @Size(max = 26) String executionProfileId,
                                   @Size(max = 100) Map<@Size(max = 64) String, @Size(max = 10_000) String> overrides) {
    }

    public record ExecutionPreviewRequest(@Size(max = 26) String executionProfileId,
                                          @Size(max = 100) Map<@Size(max = 64) String, @Size(max = 10_000) String> overrides) {
    }
}
