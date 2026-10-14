package io.testhub.platform.apiautomation;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/api-automation/history")
public class ApiAutomationHistorySyncController {
    private final ApiAutomationHistorySyncService history;
    public ApiAutomationHistorySyncController(ApiAutomationHistorySyncService history) { this.history = history; }
    @GetMapping("/sync")
    public ApiResponse<ApiAutomationHistorySyncService.Inventory> inventory(Authentication authentication, @PathVariable String projectId, @RequestParam String expectedUserId) {
        return ApiResponse.of(history.inventory(actor(authentication), projectId, expectedUserId));
    }
    @PostMapping("/sync")
    public ApiResponse<ApiAutomationHistorySyncService.Receipt> apply(Authentication authentication, @PathVariable String projectId, @RequestBody byte[] body) {
        return ApiResponse.of(history.apply(actor(authentication), projectId, history.decode(body)));
    }
    @GetMapping
    public ApiResponse<ApiAutomationHistorySyncService.Inventory> list(Authentication authentication, @PathVariable String projectId) {
        return ApiResponse.of(history.inventory(actor(authentication), projectId, actor(authentication).userId()));
    }
    @GetMapping("/{key}")
    public ApiResponse<ApiAutomationHistorySyncService.Item> detail(Authentication authentication, @PathVariable String projectId, @PathVariable String key) {
        return ApiResponse.of(history.read(actor(authentication), projectId, actor(authentication).userId(), key));
    }
    @GetMapping(value = "/{key}/export", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> export(Authentication authentication, @PathVariable String projectId, @PathVariable String key) {
        var item = history.read(actor(authentication), projectId, actor(authentication).userId(), key);
        byte[] bytes = history.encoded(item);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).eTag('"' + history.hash(bytes) + '"')
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-TestHub-Report-SHA256", history.hash(bytes))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"testhub-api-history-" + key + ".json\"").body(bytes);
    }
    private AuthenticatedPrincipal actor(Authentication authentication) { return (AuthenticatedPrincipal) authentication.getPrincipal(); }
}
