package io.testhub.platform.model;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
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
@RequestMapping("/api/v1/model-providers")
public class ModelProviderController {
    private final ModelProviderService providers;

    public ModelProviderController(ModelProviderService providers) {
        this.providers = providers;
    }

    @GetMapping
    public ApiResponse<List<Provider>> list(Authentication authentication) {
        return ApiResponse.of(providers.list(principal(authentication)));
    }

    @PostMapping
    public ApiResponse<Provider> create(Authentication authentication, @Valid @RequestBody ProviderInput input) {
        return ApiResponse.of(providers.save(principal(authentication), null, input));
    }

    @PutMapping("/{id}")
    public ApiResponse<Provider> update(Authentication authentication, @PathVariable String id,
                                        @Valid @RequestBody ProviderInput input) {
        return ApiResponse.of(providers.save(principal(authentication), id, input));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(Authentication authentication, @PathVariable String id) {
        providers.delete(principal(authentication), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/discover")
    public ApiResponse<List<Model>> discover(Authentication authentication, @Valid @RequestBody DiscoveryInput input) {
        return ApiResponse.of(providers.discover(principal(authentication), input));
    }

    @PostMapping("/{id}/test")
    public ApiResponse<TestResult> test(Authentication authentication, @PathVariable String id,
                                       @Valid @RequestBody TestInput input) {
        return ApiResponse.of(providers.test(principal(authentication), id, input));
    }

    private AuthenticatedPrincipal principal(Authentication authentication) {
        return (AuthenticatedPrincipal) authentication.getPrincipal();
    }

    public enum Protocol { CHAT_COMPLETIONS, RESPONSES }

    public enum RetryPolicy { TRANSIENT_ERRORS, DISABLED }

    public enum ReasoningLevel { none, minimal, low, medium, high, xhigh, max }

    public record ReasoningOption(@NotNull ReasoningLevel level, @NotBlank @Size(max = 80) String apiValue) { }

    public record Model(@NotBlank @Size(max = 200) String id, @Size(max = 120) String displayName,
                        @Positive Integer contextWindow, @Positive Integer maxOutputTokens, boolean acceptsImages,
                        @Size(max = 7) List<@NotNull @Valid ReasoningOption> reasoningOptions) {
        public Model { reasoningOptions = reasoningOptions == null ? List.of() : reasoningOptions; }
    }

    public record Provider(String id, String displayName, String apiBaseUrl, Protocol protocol, RetryPolicy retryPolicy,
                           boolean apiKeyConfigured, List<Model> models, long version) { }

    public record ProviderInput(@NotBlank @Size(max = 120) String displayName,
                                @NotBlank @Size(max = 2000) String apiBaseUrl,
                                @NotNull Protocol protocol, @NotNull RetryPolicy retryPolicy,
                                @Size(max = 4096) String apiKey, boolean clearApiKey,
                                @NotNull @Size(max = 100) List<@Valid @NotNull Model> models,
                                @Positive Long version) { }

    public record DiscoveryInput(@Size(max = 26) String providerId, @Positive Long version,
                                 @NotBlank @Size(max = 2000) String apiBaseUrl, @NotNull RetryPolicy retryPolicy,
                                 @Size(max = 4096) String apiKey, boolean clearApiKey) { }

    public record TestInput(@NotBlank @Size(max = 200) String modelId, ReasoningLevel reasoningLevel) { }

    public record TestResult(String modelId, String reply, long durationMs) { }
}
