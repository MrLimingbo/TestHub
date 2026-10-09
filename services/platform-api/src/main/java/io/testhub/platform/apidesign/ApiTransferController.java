package io.testhub.platform.apidesign;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/api-design/transfer")
public class ApiTransferController {
    private final ApiTransferService transfer;
    public ApiTransferController(ApiTransferService transfer) { this.transfer = transfer; }

    @GetMapping("/capabilities")
    public ApiResponse<Map<String, Object>> capabilities(Authentication authentication, @PathVariable String projectId) {
        return ApiResponse.of(transfer.capabilities((AuthenticatedPrincipal) authentication.getPrincipal(), projectId));
    }

    @GetMapping
    public ApiResponse<ApiTransferService.Snapshot> export(Authentication authentication, @PathVariable String projectId,
                                                          @RequestParam(defaultValue = "false") boolean includeFiles,
                                                          @RequestParam(defaultValue = "false") boolean includeGrpc,
                                                          @RequestParam(defaultValue = "false") boolean includeHistory) {
        return ApiResponse.of(transfer.export((AuthenticatedPrincipal) authentication.getPrincipal(), projectId, includeFiles, includeGrpc, includeHistory));
    }

    @PostMapping
    public ApiResponse<ApiTransferService.Receipt> transfer(Authentication authentication, @PathVariable String projectId,
                                                            @RequestBody byte[] body) {
        return ApiResponse.of(transfer.transfer((AuthenticatedPrincipal) authentication.getPrincipal(), projectId, transfer.decode(body)));
    }
}
