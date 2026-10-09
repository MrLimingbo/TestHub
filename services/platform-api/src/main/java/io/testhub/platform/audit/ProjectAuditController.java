package io.testhub.platform.audit;

import io.testhub.platform.access.AccessService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import java.time.Instant;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/audit-logs")
public class ProjectAuditController {
    private final AccessService access;
    private final AuditService audit;

    public ProjectAuditController(AccessService access, AuditService audit) {
        this.access = access; this.audit = audit;
    }

    @GetMapping
    public ApiResponse<List<Entry>> recent(Authentication authentication, @PathVariable String projectId,
            @RequestParam(required = false) String action, @RequestParam(defaultValue = "100") int limit) {
        access.requireProjectRead((AuthenticatedPrincipal) authentication.getPrincipal(), projectId);
        return ApiResponse.of(audit.recent(projectId, action == null || action.isBlank() ? null : action, limit).stream()
                .map(e -> new Entry(e.id(), e.userId(), e.projectId(), e.action(), e.resourceType(), e.resourceId(),
                        e.outcome(), e.requestId(), e.createdAt())).toList());
    }

    // Project readers receive visible activity fields, without system audit details or client IPs.
    public record Entry(String id, String userId, String projectId, String action, String resourceType,
                        String resourceId, String outcome, String requestId, Instant createdAt) { }
}
