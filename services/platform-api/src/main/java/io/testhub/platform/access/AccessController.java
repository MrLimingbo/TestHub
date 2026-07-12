package io.testhub.platform.access;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import io.testhub.platform.parameter.ProjectParameterService;
import io.testhub.platform.parameter.EnvironmentSecretService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AccessController {
    private final AccessService access;
    private final ProjectParameterService parameters;
    private final EnvironmentSecretService secrets;

    public AccessController(AccessService access, ProjectParameterService parameters, EnvironmentSecretService secrets) {
        this.access = access;
        this.parameters = parameters;
        this.secrets = secrets;
    }

    @GetMapping("/access/context")
    public ApiResponse<AccessService.AccessContext> context(Authentication authentication) {
        return ApiResponse.of(access.context(principal(authentication)));
    }

    @GetMapping("/access/roles")
    public ApiResponse<List<AccessService.RoleDefinition>> roles() {
        return ApiResponse.of(AccessService.ROLE_DEFINITIONS);
    }

    @GetMapping("/projects/{projectId}/members")
    public ApiResponse<List<ProjectMember>> members(Authentication authentication, @PathVariable String projectId) {
        return ApiResponse.of(access.members(principal(authentication), projectId));
    }

    @PostMapping("/projects/{projectId}/members")
    public ApiResponse<ProjectMember> saveMember(Authentication authentication, @PathVariable String projectId,
                                                   @Valid @RequestBody MemberRequest request) {
        return ApiResponse.of(access.upsertMember(principal(authentication), projectId, request.email(), request.roleCode()));
    }

    @DeleteMapping("/projects/{projectId}/members/{membershipId}")
    public ResponseEntity<Void> removeMember(Authentication authentication, @PathVariable String projectId,
                                               @PathVariable String membershipId) {
        access.removeMember(principal(authentication), projectId, membershipId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/projects/{projectId}/access-tokens")
    public ApiResponse<List<ProjectAccessToken>> tokens(Authentication authentication, @PathVariable String projectId) {
        return ApiResponse.of(access.tokens(principal(authentication), projectId));
    }

    @PostMapping("/projects/{projectId}/access-tokens")
    public ApiResponse<AccessService.CreatedToken> createToken(Authentication authentication, @PathVariable String projectId,
                                                                  @Valid @RequestBody TokenRequest request) {
        return ApiResponse.of(access.createToken(principal(authentication), projectId, request.name(), request.scopes(),
                request.expiresInDays()));
    }

    @PostMapping("/projects/{projectId}/access-tokens/{tokenId}/revoke")
    public ResponseEntity<Void> revokeToken(Authentication authentication, @PathVariable String projectId,
                                             @PathVariable String tokenId) {
        access.revokeToken(principal(authentication), projectId, tokenId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/projects/{projectId}/parameters")
    public ApiResponse<ProjectParameterService.ParameterCollection> parameters(Authentication authentication,
                                                                                 @PathVariable String projectId,
                                                                                 @org.springframework.web.bind.annotation.RequestParam(required = false)
                                                                                 String environment) {
        return ApiResponse.of(parameters.list(principal(authentication), projectId, environment));
    }

    @PostMapping("/projects/{projectId}/parameters")
    public ResponseEntity<Void> saveParameter(Authentication authentication, @PathVariable String projectId,
                                               @Valid @RequestBody ParameterRequest request) {
        parameters.upsert(principal(authentication), projectId, request.environmentCode(), request.key(), request.value());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/projects/{projectId}/parameters/{parameterId}")
    public ResponseEntity<Void> deleteParameter(Authentication authentication, @PathVariable String projectId,
                                                 @PathVariable String parameterId) {
        parameters.delete(principal(authentication), projectId, parameterId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/projects/{projectId}/secrets")
    public ApiResponse<EnvironmentSecretService.SecretCollection> secrets(Authentication authentication,
                                                                            @PathVariable String projectId,
                                                                            @org.springframework.web.bind.annotation.RequestParam(required = false)
                                                                            String environment) {
        return ApiResponse.of(secrets.list(principal(authentication), projectId, environment));
    }

    @PostMapping("/projects/{projectId}/secrets")
    public ResponseEntity<Void> saveSecret(Authentication authentication, @PathVariable String projectId,
                                            @Valid @RequestBody SecretRequest request) {
        secrets.upsert(principal(authentication), projectId, request.environmentCode(), request.key(), request.value());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/projects/{projectId}/secrets/{secretId}")
    public ResponseEntity<Void> deleteSecret(Authentication authentication, @PathVariable String projectId,
                                              @PathVariable String secretId) {
        secrets.delete(principal(authentication), projectId, secretId);
        return ResponseEntity.noContent().build();
    }

    private AuthenticatedPrincipal principal(Authentication authentication) {
        return (AuthenticatedPrincipal) authentication.getPrincipal();
    }

    public record MemberRequest(@NotBlank @Email String email, @NotBlank String roleCode) {
    }

    public record TokenRequest(@NotBlank @Size(max = 120) String name, @NotEmpty List<String> scopes,
                               Integer expiresInDays) {
    }

    public record ParameterRequest(@NotBlank @Size(max = 64) String environmentCode,
                                   @NotBlank @Size(max = 128) String key,
                                   @NotBlank @Size(max = 20_000) String value) {
    }

    public record SecretRequest(@NotBlank @Size(max = 64) String environmentCode,
                                @NotBlank @Size(max = 128) String key,
                                @NotBlank @Size(max = 20_000) String value) {
    }
}
