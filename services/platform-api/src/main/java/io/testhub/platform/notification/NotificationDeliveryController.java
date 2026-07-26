package io.testhub.platform.notification;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/notification-deliveries")
public class NotificationDeliveryController {
    private final NotificationDeliveryService deliveries;

    public NotificationDeliveryController(NotificationDeliveryService deliveries) {
        this.deliveries = deliveries;
    }

    @GetMapping
    public ApiResponse<List<NotificationDelivery>> list(Authentication authentication, @PathVariable String projectId) {
        return ApiResponse.of(deliveries.list(principal(authentication), projectId));
    }

    @PostMapping("/{deliveryId}/retry")
    public ApiResponse<NotificationDelivery> retry(Authentication authentication, @PathVariable String projectId,
                                                   @PathVariable String deliveryId) {
        return ApiResponse.of(deliveries.retry(principal(authentication), projectId, deliveryId));
    }

    private AuthenticatedPrincipal principal(Authentication authentication) {
        return (AuthenticatedPrincipal) authentication.getPrincipal();
    }
}
