package io.testhub.platform.apidebug;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import io.testhub.platform.functional.FunctionalTransferFiles;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/api-debug/grpc/descriptors/sync")
public class GrpcDescriptorSyncController {
    private final GrpcDescriptorSyncService sync;
    public GrpcDescriptorSyncController(GrpcDescriptorSyncService sync){this.sync=sync;}
    @GetMapping public ApiResponse<GrpcDescriptorSyncService.Snapshot> snapshot(Authentication auth,@PathVariable String projectId,@RequestParam String expectedUserId){return ApiResponse.of(sync.snapshot(actor(auth),projectId,expectedUserId));}
    @GetMapping("/{id}/content") public ResponseEntity<byte[]> content(Authentication auth,@PathVariable String projectId,@PathVariable String id,@RequestParam String expectedUserId){var bytes=sync.content(actor(auth),projectId,expectedUserId,id);return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(bytes.length).header("Content-Disposition","attachment; filename=\"descriptor.pb\"").header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff").header("X-Content-SHA256",FunctionalTransferFiles.sha256(bytes)).body(bytes);}
    @PostMapping public ApiResponse<GrpcDescriptorSyncService.Receipt> apply(Authentication auth,@PathVariable String projectId,@RequestBody byte[] body){return ApiResponse.of(sync.apply(actor(auth),projectId,sync.decode(body)));}
    private AuthenticatedPrincipal actor(Authentication auth){return (AuthenticatedPrincipal)auth.getPrincipal();}
}
