package io.testhub.platform.parameter;

import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ProjectSummary;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProjectParameterService {
    private static final Set<String> PARAMETER_WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD");
    private final ProjectParameterRepository parameters;
    private final AccessService access;
    private final AuditService audit;

    public ProjectParameterService(ProjectParameterRepository parameters, AccessService access, AuditService audit) {
        this.parameters = parameters;
        this.access = access;
        this.audit = audit;
    }

    public ParameterCollection list(AuthenticatedPrincipal principal, String projectId, String environmentCode) {
        access.requireProjectRead(principal, projectId);
        String normalizedEnvironment = environmentCode == null || environmentCode.isBlank() ? null : normalizeEnvironment(environmentCode);
        return new ParameterCollection(parameters.findEnvironmentCodes(projectId),
                parameters.findByProject(projectId, normalizedEnvironment));
    }

    @Transactional
    public void upsert(AuthenticatedPrincipal principal, String projectId, String environmentCode, String key, String value) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, PARAMETER_WRITERS);
        String normalizedEnvironment = normalizeEnvironment(environmentCode);
        String normalizedKey = normalizeKey(key);
        if ("BASE_URL".equals(normalizedKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "BASE_URL 由环境基础地址维护，请前往环境管理修改");
        }
        if (value == null || value.length() > 20_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "参数值不能为空且不能超过 20000 字符");
        }
        parameters.upsert(UlidCreator.getUlid().toString(), project.organizationId(), project.id(), normalizedEnvironment,
                normalizedKey, value, principal.userId());
        audit.record(principal, project.organizationId(), project.id(), "PROJECT_PARAMETER_UPSERTED", "PROJECT_PARAMETER",
                normalizedEnvironment + ":" + normalizedKey,
                java.util.Map.of("environment", normalizedEnvironment, "key", normalizedKey));
    }

    @Transactional
    public void delete(AuthenticatedPrincipal principal, String projectId, String parameterId) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, PARAMETER_WRITERS);
        parameters.delete(parameterId, projectId);
        audit.record(principal, project.organizationId(), project.id(), "PROJECT_PARAMETER_DELETED", "PROJECT_PARAMETER",
                parameterId, java.util.Map.of());
    }

    static String normalizeEnvironment(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "环境标识仅支持小写字母、数字、下划线和连字符");
        }
        return normalized;
    }

    static String normalizeKey(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.matches("[A-Za-z_][A-Za-z0-9_.-]{0,127}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "参数名称格式不合法");
        }
        return normalized;
    }

    public record ParameterCollection(List<String> environments, List<ProjectParameter> parameters) {
    }
}
