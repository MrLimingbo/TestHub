package io.testhub.platform.apiautomation;

import io.testhub.platform.shared.api.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public, HMAC-authenticated entry point for CI systems that cannot hold a PAT. */
@RestController
@RequestMapping("/api/v1/integrations/api-automation/webhooks")
public class ApiAutomationWebhookInboundController {
    private final ApiAutomationWebhookTriggerService webhooks;

    public ApiAutomationWebhookInboundController(ApiAutomationWebhookTriggerService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping(value = "/{webhookId}/trigger", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<ApiAutomationExecution> trigger(@PathVariable String webhookId,
                                                        @RequestHeader(value = "X-TestHub-Webhook-Timestamp", required = false) String timestamp,
                                                        @RequestHeader(value = "X-TestHub-Webhook-Nonce", required = false) String nonce,
                                                        @RequestHeader(value = "X-TestHub-Webhook-Signature", required = false) String signature,
                                                        @RequestBody String body) {
        return ApiResponse.of(webhooks.trigger(webhookId, timestamp, nonce, signature, body));
    }
}
