package io.testhub.platform.administration;

import io.testhub.platform.access.AccessService;
import io.testhub.platform.audit.AuditLogEntry;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.config.SystemConfig;
import io.testhub.platform.config.SystemConfigService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AdministrationController {
    private final SystemConfigService configs;
    private final AuditService audit;
    private final AccessService access;

    public AdministrationController(SystemConfigService configs, AuditService audit, AccessService access) {
        this.configs = configs;
        this.audit = audit;
        this.access = access;
    }

    @GetMapping("/system-config")
    public ApiResponse<List<SystemConfig>> configs(Authentication authentication) {
        return ApiResponse.of(configs.list(principal(authentication)));
    }

    @PostMapping("/system-config")
    public ResponseEntity<Void> saveConfig(Authentication authentication, @Valid @RequestBody ConfigRequest request) {
        configs.upsert(principal(authentication), request.key(), request.value());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/system-config/{key}")
    public ResponseEntity<Void> deleteConfig(Authentication authentication, @PathVariable String key) {
        configs.delete(principal(authentication), key);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/audit-logs")
    public ApiResponse<List<AuditLogEntry>> auditLogs(Authentication authentication,
                                                        @RequestParam(required = false) String projectId,
                                                        @RequestParam(required = false) String action,
                                                        @RequestParam(defaultValue = "50") int limit) {
        AuthenticatedPrincipal principal = principal(authentication);
        access.requireSystemAdmin(principal);
        return ApiResponse.of(audit.recent(projectId, action, limit));
    }

    private AuthenticatedPrincipal principal(Authentication authentication) {
        return (AuthenticatedPrincipal) authentication.getPrincipal();
    }

    public record ConfigRequest(@NotBlank @Size(max = 128) String key, @NotBlank @Size(max = 20_000) String value) {
    }
}
