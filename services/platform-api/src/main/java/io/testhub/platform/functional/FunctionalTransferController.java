package io.testhub.platform.functional;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/functional/transfer")
public class FunctionalTransferController {
    private final FunctionalTransferService transfer;
    public FunctionalTransferController(FunctionalTransferService transfer) { this.transfer = transfer; }
    @GetMapping("/capabilities") public ApiResponse<java.util.Map<String, Object>> capabilities(Authentication authentication, @PathVariable String projectId) {
        return ApiResponse.of(transfer.capabilities((AuthenticatedPrincipal) authentication.getPrincipal(), projectId));
    }
    @GetMapping public ApiResponse<FunctionalTransferService.Snapshot> export(Authentication authentication, @PathVariable String projectId,
                                                                            @RequestParam(defaultValue = "1") int version,
                                                                            @RequestParam(required = false) Boolean includePlans,
                                                                            @RequestParam(required = false) Boolean includeGovernance,
                                                                            @RequestParam(required = false) Boolean includeDefects,
                                                                            @RequestParam(required = false) Boolean includeFiles,
                                                                            @RequestParam(required = false) Boolean includeHistory,
                                                                            @RequestParam(required = false) Boolean includeProjectVersions,
                                                                            @RequestParam(required = false) Boolean includeGovernanceCatalogs) {
        return ApiResponse.of(transfer.export((AuthenticatedPrincipal) authentication.getPrincipal(), projectId, version,
                includePlans == null ? version >= 2 : includePlans, includeGovernance == null ? version >= 3 : includeGovernance, includeDefects == null ? version >= 4 : includeDefects,
                includeFiles == null ? version >= 5 : includeFiles, includeHistory == null ? version >= 6 : includeHistory, includeProjectVersions == null ? version >= 7 : includeProjectVersions, includeGovernanceCatalogs == null ? version >= 8 : includeGovernanceCatalogs));
    }
    @PostMapping public ApiResponse<FunctionalTransferService.Receipt> apply(Authentication authentication, @PathVariable String projectId,
                                                                           @Valid @RequestBody FunctionalTransferService.ImportRequest request) {
        return ApiResponse.of(transfer.transfer((AuthenticatedPrincipal) authentication.getPrincipal(), projectId, request));
    }
}
