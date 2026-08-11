package io.testhub.platform.parameter;

import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ProjectSummary;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.net.URI;
import java.util.List;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProjectEnvironmentService {
    private static final Set<String> ENVIRONMENT_WRITERS = Set.of(
            "SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD");
    private final ProjectEnvironmentRepository environments;
    private final ProjectParameterRepository parameters;
    private final AccessService access;
    private final AuditService audit;

    public ProjectEnvironmentService(ProjectEnvironmentRepository environments,
                                     ProjectParameterRepository parameters,
                                     AccessService access,
                                     AuditService audit) {
        this.environments = environments;
        this.parameters = parameters;
        this.access = access;
        this.audit = audit;
    }

    public List<ProjectEnvironment> list(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectRead(principal, projectId);
        return environments.findByProject(projectId);
    }

    @Transactional
    public ProjectEnvironment create(AuthenticatedPrincipal principal, String projectId, CreateInput input) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, ENVIRONMENT_WRITERS);
        String name = normalizeName(input.name());
        String code = ProjectParameterService.normalizeEnvironment(input.environmentCode());
        String baseUrl = normalizeBaseUrl(input.baseUrl());
        String description = normalizeDescription(input.description());
        boolean firstEnvironment = environments.count(projectId) == 0;
        boolean makeDefault = firstEnvironment || input.defaultEnvironment();
        boolean enabled = makeDefault || input.enabled();
        String id = UlidCreator.getUlid().toString();
        try {
            if (makeDefault) environments.clearDefault(projectId);
            environments.insert(id, project.organizationId(), projectId, name, code, baseUrl, description,
                    enabled, makeDefault, principal.userId());
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前项目已存在相同环境标识");
        }
        syncBaseUrl(project, code, baseUrl, principal.userId());
        audit.record(principal, project.organizationId(), projectId, "PROJECT_ENVIRONMENT_CREATED",
                "PROJECT_ENVIRONMENT", id, java.util.Map.of(
                        "name", name, "environmentCode", code, "defaultEnvironment", makeDefault));
        return requireEnvironment(projectId, id);
    }

    @Transactional
    public ProjectEnvironment update(AuthenticatedPrincipal principal, String projectId, String environmentId,
                                     UpdateInput input) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, ENVIRONMENT_WRITERS);
        ProjectEnvironment current = requireEnvironment(projectId, environmentId);
        String name = normalizeName(input.name());
        String baseUrl = normalizeBaseUrl(input.baseUrl());
        String description = normalizeDescription(input.description());
        if (current.defaultEnvironment() && !input.enabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "默认环境不能停用，请先设置其他默认环境");
        }
        int updated = environments.update(projectId, environmentId, name, baseUrl, description, input.enabled(),
                input.expectedVersion(), principal.userId());
        if (updated == 0) {
            if (environments.find(projectId, environmentId).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "环境不存在");
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "环境已被其他成员更新，请刷新后重试");
        }
        syncBaseUrl(project, current.environmentCode(), baseUrl, principal.userId());
        audit.record(principal, project.organizationId(), projectId, "PROJECT_ENVIRONMENT_UPDATED",
                "PROJECT_ENVIRONMENT", environmentId, java.util.Map.of(
                        "name", name, "environmentCode", current.environmentCode(), "enabled", input.enabled()));
        return requireEnvironment(projectId, environmentId);
    }

    @Transactional
    public ProjectEnvironment makeDefault(AuthenticatedPrincipal principal, String projectId, String environmentId) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, ENVIRONMENT_WRITERS);
        ProjectEnvironment current = requireEnvironment(projectId, environmentId);
        environments.clearDefault(projectId);
        environments.setDefault(projectId, environmentId, principal.userId());
        audit.record(principal, project.organizationId(), projectId, "PROJECT_ENVIRONMENT_DEFAULT_CHANGED",
                "PROJECT_ENVIRONMENT", environmentId,
                java.util.Map.of("environmentCode", current.environmentCode()));
        return requireEnvironment(projectId, environmentId);
    }

    @Transactional
    public void delete(AuthenticatedPrincipal principal, String projectId, String environmentId) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, ENVIRONMENT_WRITERS);
        ProjectEnvironment current = requireEnvironment(projectId, environmentId);
        if (environments.count(projectId) <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "项目至少需要保留一个环境");
        }
        if (current.defaultEnvironment()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "默认环境不能删除，请先设置其他默认环境");
        }
        if (environments.referenceCount(projectId, current.environmentCode()) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "环境正在被接口用例、自动化场景或运行配置使用，解除关联后才能删除");
        }
        parameters.deleteByEnvironment(projectId, current.environmentCode());
        environments.delete(projectId, environmentId);
        audit.record(principal, project.organizationId(), projectId, "PROJECT_ENVIRONMENT_DELETED",
                "PROJECT_ENVIRONMENT", environmentId,
                java.util.Map.of("name", current.name(), "environmentCode", current.environmentCode()));
    }

    private ProjectEnvironment requireEnvironment(String projectId, String environmentId) {
        return environments.find(projectId, environmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "环境不存在"));
    }

    private void syncBaseUrl(ProjectSummary project, String environmentCode, String baseUrl, String userId) {
        if (baseUrl.isBlank()) {
            parameters.deleteNonSecretKey(project.id(), environmentCode, "BASE_URL");
            return;
        }
        parameters.upsert(UlidCreator.getUlid().toString(), project.organizationId(), project.id(), environmentCode,
                "BASE_URL", baseUrl, userId);
    }

    private String normalizeName(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 160) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "环境名称长度应在 1 到 160 字符之间");
        }
        return normalized;
    }

    private String normalizeBaseUrl(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) return "";
        if (normalized.length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "环境基础地址不能超过 2000 字符");
        }
        try {
            URI uri = URI.create(normalized);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "环境基础地址必须是无账号、查询参数和片段的 HTTP(S) URL");
        }
        while (normalized.endsWith("/") && normalized.indexOf("//") < normalized.length() - 2) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String normalizeDescription(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > 1200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "环境说明不能超过 1200 字符");
        }
        return normalized;
    }

    public record CreateInput(String name, String environmentCode, String baseUrl, String description,
                              boolean enabled, boolean defaultEnvironment) {
    }

    public record UpdateInput(String name, String baseUrl, String description, boolean enabled,
                              int expectedVersion) {
    }
}
