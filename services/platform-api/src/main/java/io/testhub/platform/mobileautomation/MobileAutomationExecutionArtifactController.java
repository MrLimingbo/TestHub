package io.testhub.platform.mobileautomation;

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
@RequestMapping("/api/v1/projects/{projectId}/mobile-automation/executions/{executionId}/artifacts")
public class MobileAutomationExecutionArtifactController {
    private final MobileAutomationExecutionArtifactService artifacts;
    public MobileAutomationExecutionArtifactController(MobileAutomationExecutionArtifactService artifacts) { this.artifacts = artifacts; }
    @GetMapping public ApiResponse<List<MobileAutomationExecutionArtifact>> list(Authentication authentication,@PathVariable String projectId,@PathVariable String executionId){return ApiResponse.of(artifacts.list((AuthenticatedPrincipal)authentication.getPrincipal(),projectId,executionId));}
    @GetMapping("/{artifactId}/download") public ResponseEntity<InputStreamResource> download(Authentication authentication,@PathVariable String projectId,@PathVariable String executionId,@PathVariable String artifactId){MobileAutomationExecutionArtifactService.Download download=artifacts.download((AuthenticatedPrincipal)authentication.getPrincipal(),projectId,executionId,artifactId);HttpHeaders headers=headers(download,false);return ResponseEntity.ok().headers(headers).body(new InputStreamResource(download.content()));}
    @GetMapping("/{artifactId}/view") public ResponseEntity<InputStreamResource> view(Authentication authentication,@PathVariable String projectId,@PathVariable String executionId,@PathVariable String artifactId){MobileAutomationExecutionArtifactService.Download download=artifacts.download((AuthenticatedPrincipal)authentication.getPrincipal(),projectId,executionId,artifactId);HttpHeaders headers=headers(download,true);return ResponseEntity.ok().headers(headers).body(new InputStreamResource(download.content()));}
    private HttpHeaders headers(MobileAutomationExecutionArtifactService.Download download,boolean inline){HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.parseMediaType(download.artifact().contentType()));headers.setContentLength(download.artifact().sizeBytes());headers.setContentDisposition((inline?ContentDisposition.inline():ContentDisposition.attachment()).filename(download.artifact().originalName()).build());headers.set("X-Content-Type-Options","nosniff");return headers;}
}
