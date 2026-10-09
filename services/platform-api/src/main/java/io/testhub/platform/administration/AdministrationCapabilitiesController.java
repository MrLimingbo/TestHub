package io.testhub.platform.administration;

import io.testhub.platform.access.AccessService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdministrationCapabilitiesController {
    private final AccessService access;
    public AdministrationCapabilitiesController(AccessService access) { this.access = access; }

    @GetMapping("/api/v1/administration/capabilities")
    public ApiResponse<Capabilities> capabilities(Authentication authentication) {
        return ApiResponse.of(new Capabilities(access.canManageSystem((AuthenticatedPrincipal) authentication.getPrincipal())));
    }
    public record Capabilities(boolean systemAdministration) { }
}
