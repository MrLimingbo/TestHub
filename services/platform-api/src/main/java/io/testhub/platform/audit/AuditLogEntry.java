package io.testhub.platform.audit;

import java.time.Instant;
import java.util.Map;

public record AuditLogEntry(String id, String userId, String organizationId, String projectId, String action,
                            String resourceType, String resourceId, String outcome, String requestId,
                            String clientIp, Map<String, Object> detail, Instant createdAt) {
}
