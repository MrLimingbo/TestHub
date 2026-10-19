package io.testhub.platform.uiautomation;

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
@RequestMapping("/api/v1/projects/{projectId}/ui-automation")
public class UiAutomationArtifactSyncController {
    private final UiAutomationArtifactSyncService sync;
    public UiAutomationArtifactSyncController(UiAutomationArtifactSyncService sync) { this.sync = sync; }
    @GetMapping("/artifacts/sync")
    public ApiResponse<UiAutomationArtifactSyncService.Inventory> inventory(Authentication authentication, @PathVariable String projectId, @RequestParam String expectedUserId) {
        return ApiResponse.of(sync.inventory(actor(authentication),projectId,expectedUserId));
    }
    @PostMapping("/artifacts/sync")
    public ApiResponse<UiAutomationArtifactSyncService.Receipt> apply(Authentication authentication, @PathVariable String projectId, @RequestBody byte[] body) {
        return ApiResponse.of(sync.apply(actor(authentication),projectId,sync.decode(body)));
    }
    @GetMapping("/history/{key}/artifacts")
    public ApiResponse<List<UiAutomationArtifactSyncService.Entry>> list(Authentication authentication, @PathVariable String projectId, @PathVariable String key) {
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
    @GetMapping("/artifacts/{key}/view")
    public ResponseEntity<InputStreamResource> view(Authentication authentication,@PathVariable String projectId,@PathVariable String key) {
        var value=sync.download(actor(authentication),projectId,key);
        if(!java.util.Set.of("image/png","video/webm").contains(value.artifact().contentType()))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_ACCEPTABLE,"仅截图和视频支持在线查看");
        var headers=new HttpHeaders();headers.setContentType(MediaType.parseMediaType(value.artifact().contentType()));headers.setContentLength(value.artifact().sizeBytes());
        headers.setContentDisposition(ContentDisposition.inline().filename(value.artifact().originalName()).build());headers.setCacheControl("private, no-store");
        headers.set("X-Content-Type-Options","nosniff");headers.set("X-TestHub-Artifact-SHA256",value.artifact().checksum());
        return ResponseEntity.ok().headers(headers).body(new InputStreamResource(value.content()));
    }
    private AuthenticatedPrincipal actor(Authentication value) { return (AuthenticatedPrincipal)value.getPrincipal(); }
}
