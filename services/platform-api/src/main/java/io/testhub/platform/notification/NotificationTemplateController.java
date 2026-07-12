package io.testhub.platform.notification;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
@RequestMapping("/api/v1/projects/{projectId}/notification-templates")
public class NotificationTemplateController {
    private final NotificationTemplateService templates;

    public NotificationTemplateController(NotificationTemplateService templates) {
        this.templates = templates;
    }

    @GetMapping
    public ApiResponse<List<NotificationTemplate>> list(Authentication authentication, @PathVariable String projectId) {
        return ApiResponse.of(templates.list(principal(authentication), projectId));
    }

    @PostMapping
    public ApiResponse<NotificationTemplate> create(Authentication authentication, @PathVariable String projectId,
                                                     @Valid @RequestBody TemplateRequest request) {
        return ApiResponse.of(templates.create(principal(authentication), projectId, request.toInput()));
    }

    @PutMapping("/{templateId}")
    public ApiResponse<NotificationTemplate> update(Authentication authentication, @PathVariable String projectId,
                                                     @PathVariable String templateId,
                                                     @Valid @RequestBody TemplateRequest request) {
        return ApiResponse.of(templates.update(principal(authentication), projectId, templateId, request.toInput()));
    }

    @DeleteMapping("/{templateId}")
    public ResponseEntity<Void> delete(Authentication authentication, @PathVariable String projectId,
                                       @PathVariable String templateId) {
        templates.delete(principal(authentication), projectId, templateId);
        return ResponseEntity.noContent().build();
    }

    private AuthenticatedPrincipal principal(Authentication authentication) {
        return (AuthenticatedPrincipal) authentication.getPrincipal();
    }

    public record TemplateRequest(@NotBlank @Size(max = 120) String name,
                                  @NotBlank @Size(max = 80) String eventCode,
                                  @NotBlank String channel,
                                  @NotBlank @Size(max = 255) String subjectTemplate,
                                  @NotBlank @Size(max = 20_000) String bodyTemplate,
                                  boolean enabled) {
        public TemplateRequest {
            if (channel != null) {
                channel = channel.trim();
            }
        }

        NotificationTemplateService.TemplateInput toInput() {
            return new NotificationTemplateService.TemplateInput(name, eventCode, channel, subjectTemplate, bodyTemplate, enabled);
        }
    }
}
