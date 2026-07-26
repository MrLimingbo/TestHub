package io.testhub.platform.extension;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/extensions")
public class ExtensionRegistryController {
    private final ExtensionRegistryService extensions;

    public ExtensionRegistryController(ExtensionRegistryService extensions) {
        this.extensions = extensions;
    }

    @GetMapping("/plugins")
    public ApiResponse<List<InstalledPlugin>> plugins(Authentication authentication) {
        return ApiResponse.of(extensions.plugins(principal(authentication)));
    }

    @GetMapping("/signing-keys")
    public ApiResponse<List<PluginSigningKey>> signingKeys(Authentication authentication) {
        return ApiResponse.of(extensions.signingKeys(principal(authentication)));
    }

    @PostMapping("/signing-keys")
    public ApiResponse<PluginSigningKey> registerSigningKey(Authentication authentication, @Valid @RequestBody SigningKeyRequest request) {
        return ApiResponse.of(extensions.registerSigningKey(principal(authentication), request.toInput()));
    }

    @PostMapping("/signing-keys/{keyId}/enable")
    public ResponseEntity<Void> enableSigningKey(Authentication authentication, @PathVariable String keyId) {
        extensions.setSigningKeyEnabled(principal(authentication), keyId, true);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/signing-keys/{keyId}/revoke")
    public ResponseEntity<Void> revokeSigningKey(Authentication authentication, @PathVariable String keyId) {
        extensions.setSigningKeyEnabled(principal(authentication), keyId, false);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/plugins/packages")
    public ApiResponse<InstalledPlugin> installPackage(Authentication authentication, @Valid @RequestBody PackageRequest request) {
        return ApiResponse.of(extensions.installPackage(principal(authentication), request.toInput()));
    }

    @PostMapping("/plugins/{pluginId}/enable")
    public ApiResponse<InstalledPlugin> enablePlugin(Authentication authentication, @PathVariable String pluginId) {
        return ApiResponse.of(extensions.setPluginEnabled(principal(authentication), pluginId, true));
    }

    @PostMapping("/plugins/{pluginId}/disable")
    public ApiResponse<InstalledPlugin> disablePlugin(Authentication authentication, @PathVariable String pluginId) {
        return ApiResponse.of(extensions.setPluginEnabled(principal(authentication), pluginId, false));
    }

    @GetMapping("/adapter-definitions")
    public ApiResponse<List<EnterpriseAdapterDefinition>> adapterDefinitions(Authentication authentication) {
        return ApiResponse.of(extensions.adapterDefinitions(principal(authentication)));
    }

    @GetMapping("/adapters")
    public ApiResponse<List<EnterpriseAdapterConfiguration>> adapters(Authentication authentication) {
        return ApiResponse.of(extensions.adapterConfigurations(principal(authentication)));
    }

    @PostMapping("/adapters")
    public ApiResponse<EnterpriseAdapterConfiguration> createAdapter(Authentication authentication,
                                                                       @Valid @RequestBody AdapterRequest request) {
        return ApiResponse.of(extensions.saveAdapter(principal(authentication), null, request.toInput()));
    }

    @PutMapping("/adapters/{adapterId}")
    public ApiResponse<EnterpriseAdapterConfiguration> updateAdapter(Authentication authentication, @PathVariable String adapterId,
                                                                       @Valid @RequestBody AdapterRequest request) {
        return ApiResponse.of(extensions.saveAdapter(principal(authentication), adapterId, request.toInput()));
    }

    @DeleteMapping("/adapters/{adapterId}")
    public ResponseEntity<Void> deleteAdapter(Authentication authentication, @PathVariable String adapterId) {
        extensions.deleteAdapter(principal(authentication), adapterId);
        return ResponseEntity.noContent().build();
    }

    private AuthenticatedPrincipal principal(Authentication authentication) {
        return (AuthenticatedPrincipal) authentication.getPrincipal();
    }

    public record SigningKeyRequest(@NotBlank @Size(max = 80) String keyId, @NotBlank @Size(max = 120) String displayName,
                                    @NotBlank @Size(max = 1024) String publicKeyBase64) {
        ExtensionRegistryService.SigningKeyInput toInput() { return new ExtensionRegistryService.SigningKeyInput(keyId, displayName, publicKeyBase64); }
    }

    public record PackageRequest(@NotBlank @Size(max = 80) String signerKeyId, @NotBlank @Size(max = 50_000) String manifestJson,
                                 @NotBlank @Size(max = 4096) String signatureBase64) {
        ExtensionRegistryService.PackageInput toInput() { return new ExtensionRegistryService.PackageInput(signerKeyId, manifestJson, signatureBase64); }
    }

    public record AdapterRequest(@NotBlank @Size(max = 32) String adapterType, @NotBlank @Size(max = 120) String displayName,
                                 @NotBlank @Size(max = 1024) String endpoint, Map<String, String> configuration, boolean enabled) {
        ExtensionRegistryService.AdapterInput toInput() {
            return new ExtensionRegistryService.AdapterInput(adapterType, displayName, endpoint, configuration, enabled);
        }
    }
}
