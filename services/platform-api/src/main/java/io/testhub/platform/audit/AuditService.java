package io.testhub.platform.audit;

import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
public class AuditService {
    private final AuditRepository repository;

    public AuditService(AuditRepository repository) {
        this.repository = repository;
    }

    public void record(AuthenticatedPrincipal principal, String organizationId, String projectId, String action,
                       String resourceType, String resourceId, Map<String, Object> detail) {
        // Quartz threads have an actor but no servlet request scope.
        HttpServletRequest request = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest() : null;
        String requestId = request == null ? "system" : (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        String clientIp = request == null ? null : request.getRemoteAddr();
        repository.insert(new AuditLogEntry(UlidCreator.getUlid().toString(), principal.userId(), organizationId, projectId,
                action, resourceType, resourceId, "SUCCESS", requestId == null ? "system" : requestId, clientIp,
                detail == null ? Map.of() : Map.copyOf(detail), null));
    }

    public void recordSystem(String organizationId, String projectId, String action, String resourceType, String resourceId,
                             Map<String, Object> detail) {
        repository.insert(new AuditLogEntry(UlidCreator.getUlid().toString(), null, organizationId, projectId, action,
                resourceType, resourceId, "SUCCESS", "system", null, detail == null ? Map.of() : Map.copyOf(detail), null));
    }

    public List<AuditLogEntry> recent(String projectId, String action, int limit) {
        return repository.findRecent(projectId, action, Math.min(Math.max(limit, 1), 200));
    }
}
