package io.testhub.platform.operations;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.security.SecretRekeyService;
import io.testhub.platform.shared.api.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operations")
public class OperationsController {
    private final OperationsService operations;
    private final SecretRekeyService rekey;

    public OperationsController(OperationsService operations, SecretRekeyService rekey) {
        this.operations = operations;
        this.rekey = rekey;
    }

    @GetMapping("/overview")
    public ApiResponse<OperationsOverview> overview(Authentication authentication) {
        return ApiResponse.of(operations.overview((AuthenticatedPrincipal) authentication.getPrincipal()));
    }

    @PostMapping("/secret-rekey")
    public ApiResponse<SecretRekeyService.RekeySummary> rekey(Authentication authentication, @RequestBody RekeyRequest request) {
        return ApiResponse.of(rekey.rekey((AuthenticatedPrincipal) authentication.getPrincipal(), request.confirmation()));
    }

    public record RekeyRequest(String confirmation) { }
}
