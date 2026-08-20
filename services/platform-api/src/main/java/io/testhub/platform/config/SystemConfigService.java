package io.testhub.platform.config;

import io.testhub.platform.apidebug.ApiDebugTargetPolicy;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SystemConfigService {
    private final SystemConfigRepository configs;
    private final AccessService access;
    private final AuditService audit;

    public SystemConfigService(SystemConfigRepository configs, AccessService access, AuditService audit) {
        this.configs = configs;
        this.access = access;
        this.audit = audit;
    }

    public List<SystemConfig> list(AuthenticatedPrincipal principal) {
        access.requireSystemAdmin(principal);
        return configs.findAllNonSecret();
    }

    @Transactional
    public void upsert(AuthenticatedPrincipal principal, String key, String value) {
        access.requireSystemAdmin(principal);
        String normalizedKey = normalizeKey(key);
        if (value == null || value.length() > 20_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "配置值不能为空且不能超过 20000 字符");
        }
        String normalizedValue = normalizeValue(normalizedKey, value);
        configs.upsert(normalizedKey, normalizedValue, principal.userId());
        audit.record(principal, null, null, "SYSTEM_CONFIG_UPSERTED", "SYSTEM_CONFIG", normalizedKey,
                java.util.Map.of("key", normalizedKey));
    }

    @Transactional
    public void delete(AuthenticatedPrincipal principal, String key) {
        access.requireSystemAdmin(principal);
        String normalizedKey = normalizeKey(key);
        configs.deleteNonSecret(normalizedKey);
        audit.record(principal, null, null, "SYSTEM_CONFIG_DELETED", "SYSTEM_CONFIG", normalizedKey,
                java.util.Map.of("key", normalizedKey));
    }

    private String normalizeKey(String key) {
        String normalized = key == null ? "" : key.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[a-z][a-z0-9_.-]{1,127}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "系统配置键格式不合法");
        }
        return normalized;
    }

    private String normalizeValue(String key, String value) {
        if (!ApiDebugTargetPolicy.ALLOW_CONFIGURED_PRIVATE_TARGETS_CONFIG.equals(key)) return value;
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.equals("true") && !normalized.equals("false")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "系统配置 " + ApiDebugTargetPolicy.ALLOW_CONFIGURED_PRIVATE_TARGETS_CONFIG + " 仅允许 true 或 false");
        }
        return normalized;
    }
}
