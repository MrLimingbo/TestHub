package io.testhub.platform.access;

import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.account.AccountManagementService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@org.springframework.stereotype.Service
public class AccessService {
    public static final List<RoleDefinition> ROLE_DEFINITIONS = List.of(
            new RoleDefinition("PROJECT_ADMIN", "项目管理员", "管理成员、令牌和项目配置"),
            new RoleDefinition("TEST_LEAD", "测试负责人", "管理测试资产和执行计划"),
            new RoleDefinition("TESTER", "测试工程师", "创建与执行测试资产"),
            new RoleDefinition("VIEWER", "只读成员", "查看项目数据与报告"));
    private static final Set<String> TOKEN_SCOPES = Set.of("project:read", "project:write", "run:execute");
    private static final SecureRandom RANDOM = new SecureRandom();
    private final AccessRepository access;
    private final TokenRepository tokens;
    private final AccountManagementService accountManagement;
    private final AuditService audit;

    public AccessService(AccessRepository access, TokenRepository tokens,
                         AccountManagementService accountManagement, AuditService audit) {
        this.access = access;
        this.tokens = tokens;
        this.accountManagement = accountManagement;
        this.audit = audit;
    }

    public AccessContext context(AuthenticatedPrincipal principal) {
        if (principal.tokenProjectId() != null) {
            ProjectSummary project = requireProjectAccess(principal, principal.tokenProjectId(), false);
            OrganizationSummary organization = access.findOrganizationsForUser(principal.userId()).stream()
                    .filter(candidate -> candidate.id().equals(project.organizationId())).findFirst()
                    .orElseThrow(() -> new AccessDeniedException("令牌无权访问该工作空间"));
            return new AccessContext(List.of(organization), java.util.Map.of(organization.id(), List.of(project)));
        }
        List<OrganizationSummary> organizations = access.findOrganizationsForUser(principal.userId());
        return new AccessContext(organizations, organizations.stream().collect(java.util.stream.Collectors.toMap(
                OrganizationSummary::id, organization -> access.findProjectsForUser(principal.userId(), organization.id()))));
    }

    public List<ProjectMember> members(AuthenticatedPrincipal principal, String projectId) {
        requireProjectAccess(principal, projectId, false);
        return access.findProjectMembers(projectId);
    }

    public ProjectSummary project(AuthenticatedPrincipal principal, String projectId) {
        return requireProjectAccess(principal, projectId, false);
    }

    @Transactional
    public ProjectSummary createProject(AuthenticatedPrincipal principal, String name, String code,
                                        String description) {
        OrganizationSummary organization = requireWorkspaceManage(principal);
        String normalizedCode = normalizeProjectCode(code);
        if (access.findProjectByCode(organization.id(), normalizedCode).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "项目标识已存在");
        }
        String id = UlidCreator.getUlid().toString();
        access.insertProject(id, organization.id(), normalizeProjectName(name), normalizedCode,
                normalizeDescription(description), principal.userId());
        audit.record(principal, organization.id(), id, "PROJECT_CREATED", "PROJECT", id,
                java.util.Map.of("name", name.trim(), "code", normalizedCode));
        return access.findProject(id).orElseThrow();
    }

    @Transactional
    public ProjectSummary updateProject(AuthenticatedPrincipal principal, String projectId, String name,
                                        String code, String description) {
        ProjectSummary project = requireProjectAccess(principal, projectId, true);
        String normalizedCode = normalizeProjectCode(code);
        access.findProjectByCode(project.organizationId(), normalizedCode)
                .filter(candidate -> !candidate.id().equals(projectId))
                .ifPresent(candidate -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "项目标识已存在");
                });
        access.updateProject(projectId, normalizeProjectName(name), normalizedCode, normalizeDescription(description));
        audit.record(principal, project.organizationId(), projectId, "PROJECT_UPDATED", "PROJECT", projectId,
                java.util.Map.of("name", name.trim(), "code", normalizedCode));
        return access.findProject(projectId).orElseThrow();
    }

    @Transactional
    public void deleteProject(AuthenticatedPrincipal principal, String projectId) {
        ProjectSummary project = requireProjectAccess(principal, projectId, true);
        access.softDeleteProject(projectId);
        audit.record(principal, project.organizationId(), projectId, "PROJECT_DELETED", "PROJECT", projectId,
                java.util.Map.of("name", project.name(), "code", project.code()));
    }

    public ProjectSummary requireProjectRead(AuthenticatedPrincipal principal, String projectId) {
        return requireProjectAccess(principal, projectId, false);
    }

    public ProjectSummary requireProjectParameterWrite(AuthenticatedPrincipal principal, String projectId,
                                                        Set<String> permittedRoles) {
        ProjectSummary project = access.findProject(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在"));
        if (principal.tokenProjectId() != null && !principal.tokenProjectId().equals(projectId)) {
            throw new AccessDeniedException("令牌无权访问该项目");
        }
        if (principal instanceof ApiTokenPrincipal tokenPrincipal && !tokenPrincipal.scopes().contains("project:write")) {
            throw new AccessDeniedException("令牌没有项目写入权限范围");
        }
        List<String> roles = access.findRoles(principal.userId(), project.organizationId(), projectId);
        if (roles.stream().noneMatch(permittedRoles::contains)) {
            throw new AccessDeniedException("没有修改项目参数的权限");
        }
        return project;
    }

    /**
     * Execution is intentionally narrower than a general project write. CI uses
     * the dedicated {@code run:execute} scope; browser users still require an
     * allowed project role.
     */
    public ProjectSummary requireApiAutomationExecution(AuthenticatedPrincipal principal, String projectId,
                                                         Set<String> permittedRoles) {
        ProjectSummary project = access.findProject(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在"));
        if (principal.tokenProjectId() != null && !principal.tokenProjectId().equals(projectId)) {
            throw new AccessDeniedException("令牌无权访问该项目");
        }
        if (principal.webhookTriggerId() != null || principal.automationScheduleId() != null) {
            return project;
        }
        if (principal instanceof ApiTokenPrincipal tokenPrincipal
                && !tokenPrincipal.scopes().contains("run:execute")) {
            throw new AccessDeniedException("令牌没有触发执行权限范围");
        }
        List<String> roles = access.findRoles(principal.userId(), project.organizationId(), projectId);
        if (roles.stream().noneMatch(permittedRoles::contains)) {
            throw new AccessDeniedException("没有触发接口自动化执行的权限");
        }
        return project;
    }

    public void requireSystemAdmin(AuthenticatedPrincipal principal) {
        if (principal.tokenProjectId() != null || !access.hasRole(principal.userId(), "SYSTEM_ADMIN")) {
            throw new AccessDeniedException("仅系统管理员可访问");
        }
    }

    @Transactional
    public ProjectMember upsertMember(AuthenticatedPrincipal principal, String projectId, String account, String roleCode) {
        ProjectSummary project = requireProjectAccess(principal, projectId, true);
        String normalizedRole = roleCode.trim().toUpperCase(Locale.ROOT);
        if (ROLE_DEFINITIONS.stream().noneMatch(role -> role.code().equals(normalizedRole))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的项目角色");
        }
        var userAccount = accountManagement.resolveOrCreateForProjectMember(principal, project, account);
        access.deleteProjectMemberships(userAccount.id(), projectId);
        String membershipId = UlidCreator.getUlid().toString();
        access.insertMembership(membershipId, userAccount.id(), project.organizationId(), projectId, normalizedRole);
        audit.record(principal, project.organizationId(), projectId, "MEMBERSHIP_UPSERTED", "PROJECT_MEMBER", membershipId,
                java.util.Map.of("account", userAccount.account(), "roleCode", normalizedRole));
        return access.findProjectMembers(projectId).stream().filter(member -> member.membershipId().equals(membershipId))
                .findFirst().orElseThrow();
    }

    @Transactional
    public void removeMember(AuthenticatedPrincipal principal, String projectId, String membershipId) {
        ProjectSummary project = requireProjectAccess(principal, projectId, true);
        access.deleteMembership(membershipId, projectId);
        audit.record(principal, project.organizationId(), projectId, "MEMBERSHIP_REMOVED", "PROJECT_MEMBER", membershipId,
                java.util.Map.of());
    }

    public List<ProjectAccessToken> tokens(AuthenticatedPrincipal principal, String projectId) {
        ProjectSummary project = requireProjectAccess(principal, projectId, true);
        return tokens.findActiveAndRevokedForProject(projectId);
    }

    @Transactional
    public CreatedToken createToken(AuthenticatedPrincipal principal, String projectId, String name, List<String> scopes,
                                    Integer expiresInDays) {
        ProjectSummary project = requireProjectAccess(principal, projectId, true);
        if (name == null || name.isBlank() || name.length() > 120) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "令牌名称长度应在 1 到 120 字符之间");
        }
        List<String> normalizedScopes = scopes == null || scopes.isEmpty() ? List.of("project:read") : scopes.stream()
                .map(scope -> scope.trim().toLowerCase(Locale.ROOT)).distinct().toList();
        if (!TOKEN_SCOPES.containsAll(normalizedScopes)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "令牌权限范围不受支持");
        }
        if (expiresInDays != null && (expiresInDays < 1 || expiresInDays > 365)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "有效期应在 1 到 365 天之间");
        }
        byte[] random = new byte[24];
        RANDOM.nextBytes(random);
        String rawToken = "thp_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String id = UlidCreator.getUlid().toString();
        Instant expiresAt = expiresInDays == null ? null : Instant.now().plus(expiresInDays, ChronoUnit.DAYS);
        tokens.insert(id, principal.userId(), projectId, name.trim(), rawToken.substring(0, 12), sha256(rawToken),
                normalizedScopes, expiresAt);
        audit.record(principal, project.organizationId(), projectId, "ACCESS_TOKEN_CREATED", "ACCESS_TOKEN", id,
                java.util.Map.of("name", name.trim(), "scopes", normalizedScopes));
        return new CreatedToken(id, name.trim(), rawToken.substring(0, 12), rawToken, normalizedScopes, expiresAt);
    }

    @Transactional
    public void revokeToken(AuthenticatedPrincipal principal, String projectId, String tokenId) {
        ProjectSummary project = requireProjectAccess(principal, projectId, true);
        tokens.revoke(tokenId, projectId);
        audit.record(principal, project.organizationId(), projectId, "ACCESS_TOKEN_REVOKED", "ACCESS_TOKEN", tokenId,
                java.util.Map.of());
    }

    private ProjectSummary requireProjectAccess(AuthenticatedPrincipal principal, String projectId, boolean manage) {
        ProjectSummary project = access.findProject(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在"));
        if (principal.tokenProjectId() != null && !principal.tokenProjectId().equals(projectId)) {
            throw new AccessDeniedException("令牌无权访问该项目");
        }
        if (principal instanceof ApiTokenPrincipal tokenPrincipal) {
            String requiredScope = manage ? "project:write" : "project:read";
            if (!tokenPrincipal.scopes().contains(requiredScope)) {
                throw new AccessDeniedException("令牌没有所需的项目权限范围");
            }
        }
        List<String> roles = access.findRoles(principal.userId(), project.organizationId(), projectId);
        boolean allowed = roles.contains("SYSTEM_ADMIN") || roles.contains("ORG_ADMIN")
                || (!manage && !roles.isEmpty()) || (manage && roles.contains("PROJECT_ADMIN"));
        if (!allowed) {
            throw new AccessDeniedException("没有项目访问权限");
        }
        return project;
    }

    private OrganizationSummary requireWorkspaceManage(AuthenticatedPrincipal principal) {
        if (principal.tokenProjectId() != null) {
            throw new AccessDeniedException("项目令牌不能创建项目");
        }
        return access.findOrganizationsForUser(principal.userId()).stream()
                .filter(workspace -> {
                    List<String> roles = access.findRoles(principal.userId(), workspace.id(), "");
                    return roles.contains("SYSTEM_ADMIN") || roles.contains("ORG_ADMIN");
                })
                .findFirst()
                .orElseThrow(() -> new AccessDeniedException("没有项目创建权限"));
    }

    private String normalizeProjectName(String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "项目名称不能为空");
        }
        return value.trim();
    }

    private String normalizeProjectCode(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9_-]{1,63}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "项目标识需为 2-64 位小写字母、数字、下划线或连字符");
        }
        return normalized;
    }

    private String normalizeDescription(String value) {
        return value == null ? "" : value.trim();
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法保护访问令牌", exception);
        }
    }

    public record RoleDefinition(String code, String name, String description) {
    }

    public record AccessContext(List<OrganizationSummary> organizations, java.util.Map<String, List<ProjectSummary>> projects) {
    }

    public record CreatedToken(String id, String name, String tokenPrefix, String token, List<String> scopes,
                               Instant expiresAt) {
    }
}
