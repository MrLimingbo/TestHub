package io.testhub.platform.apidebug;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/api-debug/{mode:history|replay-drafts}/archive")
public class ApiDebugHistorySyncController {
    private final ApiDebugHistorySyncService sync;
    public ApiDebugHistorySyncController(ApiDebugHistorySyncService sync){this.sync=sync;}
    @GetMapping("/sync") public ApiResponse<ApiDebugHistorySyncService.Inventory> inventory(Authentication auth,@PathVariable String projectId,@PathVariable String mode,@RequestParam String expectedUserId){return ApiResponse.of(sync.inventory(actor(auth),projectId,expectedUserId,mode));}
    @PostMapping("/sync") public ApiResponse<ApiDebugHistorySyncService.Receipt> apply(Authentication auth,@PathVariable String projectId,@PathVariable String mode,@RequestBody byte[] body){return ApiResponse.of(sync.apply(actor(auth),projectId,mode,sync.decode(body)));}
    @GetMapping public ApiResponse<ApiDebugHistorySyncService.Inventory> list(Authentication auth,@PathVariable String projectId,@PathVariable String mode){return ApiResponse.of(sync.inventory(actor(auth),projectId,actor(auth).userId(),mode));}
    @GetMapping("/{key}") public ApiResponse<ApiDebugHistorySyncService.Item> detail(Authentication auth,@PathVariable String projectId,@PathVariable String mode,@PathVariable String key){return ApiResponse.of(sync.read(actor(auth),projectId,actor(auth).userId(),mode,key));}
    @GetMapping(value="/{key}/export",produces=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<byte[]> export(Authentication auth,@PathVariable String projectId,@PathVariable String mode,@PathVariable String key){var bytes=sync.encoded(sync.read(actor(auth),projectId,actor(auth).userId(),mode,key));return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff").header("Content-Disposition","attachment; filename=\"testhub-debug-archive.json\"").body(bytes);}
    private AuthenticatedPrincipal actor(Authentication auth){return (AuthenticatedPrincipal)auth.getPrincipal();}
}
