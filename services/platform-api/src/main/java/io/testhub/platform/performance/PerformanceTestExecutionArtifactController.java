package io.testhub.platform.performance;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import java.util.List;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/performance/executions/{executionId}/artifacts")
public class PerformanceTestExecutionArtifactController {
    private final PerformanceTestExecutionArtifactService artifacts;

    public PerformanceTestExecutionArtifactController(PerformanceTestExecutionArtifactService artifacts) {
        this.artifacts = artifacts;
    }

    @GetMapping
    public ApiResponse<List<PerformanceTestExecutionArtifact>> list(Authentication authentication, @PathVariable String projectId,
                                                                     @PathVariable String executionId) {
        return ApiResponse.of(artifacts.list(principal(authentication), projectId, executionId));
    }

    @GetMapping("/{artifactId}/download")
    public ResponseEntity<InputStreamResource> download(Authentication authentication, @PathVariable String projectId,
                                                        @PathVariable String executionId, @PathVariable String artifactId) {
        PerformanceTestExecutionArtifactService.Download download = artifacts.download(principal(authentication), projectId,
                executionId, artifactId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(download.artifact().contentType()));
        headers.setContentLength(download.artifact().sizeBytes());
        headers.setContentDisposition(ContentDisposition.attachment().filename(download.artifact().originalName()).build());
        return ResponseEntity.ok().headers(headers).body(new InputStreamResource(download.content()));
    }

    private AuthenticatedPrincipal principal(Authentication authentication) {
        return (AuthenticatedPrincipal) authentication.getPrincipal();
    }
}
