package io.testhub.platform.apiautomation;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/api-automation/webhook-triggers")
public class ApiAutomationWebhookTriggerController {
    private final ApiAutomationWebhookTriggerService webhooks;

    public ApiAutomationWebhookTriggerController(ApiAutomationWebhookTriggerService webhooks) {
        this.webhooks = webhooks;
    }

    @GetMapping
    public ApiResponse<List<ApiAutomationWebhookTrigger>> list(Authentication authentication, @PathVariable String projectId) {
        return ApiResponse.of(webhooks.list(principal(authentication), projectId));
    }

    @PostMapping
    public ApiResponse<ApiAutomationWebhookTriggerService.CreatedWebhookTrigger> create(Authentication authentication,
                                                                                          @PathVariable String projectId,
                                                                                          @Valid @RequestBody CreateWebhookRequest request) {
        return ApiResponse.of(webhooks.create(principal(authentication), projectId, request.scenarioId(), request.name()));
    }

    @PutMapping("/{webhookId}")
    public ApiResponse<ApiAutomationWebhookTrigger> setEnabled(Authentication authentication, @PathVariable String projectId,
                                                                @PathVariable String webhookId,
                                                                @Valid @RequestBody UpdateWebhookRequest request) {
        return ApiResponse.of(webhooks.setEnabled(principal(authentication), projectId, webhookId, request.enabled()));
    }

    @DeleteMapping("/{webhookId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(Authentication authentication, @PathVariable String projectId, @PathVariable String webhookId) {
        webhooks.revoke(principal(authentication), projectId, webhookId);
    }

    private AuthenticatedPrincipal principal(Authentication authentication) {
        return (AuthenticatedPrincipal) authentication.getPrincipal();
    }

    public record CreateWebhookRequest(@NotBlank String name, @NotBlank String scenarioId) {
    }
    public record UpdateWebhookRequest(@NotNull Boolean enabled) {
    }
}
