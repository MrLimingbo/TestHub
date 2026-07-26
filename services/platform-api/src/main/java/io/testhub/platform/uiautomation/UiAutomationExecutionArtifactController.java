package io.testhub.platform.uiautomation;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import java.util.List;
import java.util.Set;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/ui-automation/executions/{executionId}/artifacts")
public class UiAutomationExecutionArtifactController {
    private final UiAutomationExecutionArtifactService artifacts;
    public UiAutomationExecutionArtifactController(UiAutomationExecutionArtifactService artifacts) { this.artifacts = artifacts; }
    @GetMapping
    public ApiResponse<List<UiAutomationExecutionArtifact>> list(Authentication authentication, @PathVariable String projectId, @PathVariable String executionId) {
        return ApiResponse.of(artifacts.list((AuthenticatedPrincipal) authentication.getPrincipal(), projectId, executionId));
    }
    @GetMapping("/{artifactId}/download")
    public ResponseEntity<InputStreamResource> download(Authentication authentication, @PathVariable String projectId,
                                                         @PathVariable String executionId, @PathVariable String artifactId) {
        UiAutomationExecutionArtifactService.Download download = artifacts.download((AuthenticatedPrincipal) authentication.getPrincipal(), projectId, executionId, artifactId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(download.artifact().contentType()));
        headers.setContentLength(download.artifact().sizeBytes());
        headers.setContentDisposition(ContentDisposition.attachment().filename(download.artifact().originalName()).build());
        return ResponseEntity.ok().headers(headers).body(new InputStreamResource(download.content()));
    }
    @GetMapping("/{artifactId}/view")
    public ResponseEntity<InputStreamResource> view(Authentication authentication, @PathVariable String projectId,
                                                     @PathVariable String executionId, @PathVariable String artifactId) {
        UiAutomationExecutionArtifactService.Download download = artifacts.download((AuthenticatedPrincipal) authentication.getPrincipal(), projectId, executionId, artifactId);
        if (!Set.of("image/png", "video/webm").contains(download.artifact().contentType())) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_ACCEPTABLE, "仅截图和视频支持在线查看");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(download.artifact().contentType()));
        headers.setContentLength(download.artifact().sizeBytes());
        headers.setContentDisposition(ContentDisposition.inline().filename(download.artifact().originalName()).build());
        headers.set("X-Content-Type-Options", "nosniff");
        return ResponseEntity.ok().headers(headers).body(new InputStreamResource(download.content()));
    }
}
