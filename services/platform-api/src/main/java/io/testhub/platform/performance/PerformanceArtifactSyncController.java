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
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/performance")
public class PerformanceArtifactSyncController {
    private final PerformanceArtifactSyncService sync;
    public PerformanceArtifactSyncController(PerformanceArtifactSyncService sync) { this.sync = sync; }
    @GetMapping("/artifacts/sync")
    public ApiResponse<PerformanceArtifactSyncService.Inventory> inventory(Authentication authentication, @PathVariable String projectId, @RequestParam String expectedUserId) {
        return ApiResponse.of(sync.inventory(actor(authentication),projectId,expectedUserId));
    }
    @PostMapping("/artifacts/sync")
    public ApiResponse<PerformanceArtifactSyncService.Receipt> apply(Authentication authentication, @PathVariable String projectId, @RequestBody byte[] body) {
        return ApiResponse.of(sync.apply(actor(authentication),projectId,sync.decode(body)));
    }
    @GetMapping("/history/{key}/artifacts")
    public ApiResponse<List<PerformanceArtifactSyncService.Entry>> list(Authentication authentication, @PathVariable String projectId, @PathVariable String key) {
        return ApiResponse.of(sync.list(actor(authentication),projectId,key));
    }
    @GetMapping("/artifacts/{key}/download")
    public ResponseEntity<InputStreamResource> download(Authentication authentication, @PathVariable String projectId, @PathVariable String key) {
        var value = sync.download(actor(authentication),projectId,key); var headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(value.artifact().contentType())); headers.setContentLength(value.artifact().sizeBytes());
        headers.setContentDisposition(ContentDisposition.attachment().filename(value.artifact().originalName()).build());
        headers.setCacheControl("private, no-store"); headers.setETag('"' + value.artifact().checksum() + '"');
        headers.set("X-TestHub-Artifact-SHA256",value.artifact().checksum()); headers.set("X-Content-Type-Options","nosniff");
        return ResponseEntity.ok().headers(headers).body(new InputStreamResource(value.content()));
    }
    private AuthenticatedPrincipal actor(Authentication value) { return (AuthenticatedPrincipal)value.getPrincipal(); }
}
