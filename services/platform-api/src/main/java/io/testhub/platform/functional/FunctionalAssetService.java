package io.testhub.platform.functional;

import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ProjectSummary;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FunctionalAssetService {
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private static final Set<String> PRIORITIES = Set.of("P0", "P1", "P2", "P3");
    private static final Set<String> REQUIREMENT_STATUSES = Set.of("DRAFT", "REVIEWING", "APPROVED", "ARCHIVED");
    private static final Set<String> CASE_STATUSES = Set.of("DRAFT", "READY", "BLOCKED", "ARCHIVED");
    static final Set<String> CASE_TYPES = Set.of("UNIT", "API", "FUNCTIONAL", "INSTALLATION", "CONFIGURATION", "PERFORMANCE", "SECURITY", "OTHER");
    private static final Set<String> CATALOG_SCOPES = Set.of("REQUIREMENTS", "CASES", "PLANS", "GOVERNANCE", "DELIVERY");

    private final FunctionalCatalogRepository catalogs;
    private final FunctionalRequirementRepository requirements;
    private final FunctionalCaseRepository cases;
    private final AccessService access;
    private final AuditService audit;

    public FunctionalAssetService(FunctionalCatalogRepository catalogs, FunctionalRequirementRepository requirements,
                                  FunctionalCaseRepository cases, AccessService access, AuditService audit) {
        this.catalogs = catalogs;
        this.requirements = requirements;
        this.cases = cases;
        this.access = access;
        this.audit = audit;
    }

    public List<FunctionalCatalog> listCatalogs(AuthenticatedPrincipal principal, String projectId, String scope) {
        access.requireProjectRead(principal, projectId);
        return catalogs.findByProject(projectId, normalizeCatalogScope(scope, "REQUIREMENTS"));
    }

    @Transactional
    public FunctionalCatalog createCatalog(AuthenticatedPrincipal principal, String projectId, CatalogInput input) {
        ProjectSummary project = writeAccess(principal, projectId);
        if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目录参数不能为空");
        String name = required(input.name(), 160, "目录名称");
        String scope = normalizeCatalogScope(input.scope(), "REQUIREMENTS");
        String parentId = blankToNull(input.parentId());
        validateCatalogParent(projectId, scope, parentId, null);
        String id = UlidCreator.getUlid().toString();
        try {
            catalogs.insert(id, project.organizationId(), projectId, scope, parentId, name, input.sortOrder(), principal.userId());
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "同级目录名称已存在", exception);
        }
        audit.record(principal, project.organizationId(), projectId, "FUNCTIONAL_CATALOG_CREATED", "FUNCTIONAL_CATALOG", id,
                Map.of("name", name, "scope", scope));
        return catalogs.findById(projectId, id).orElseThrow();
    }

    @Transactional
    public FunctionalCatalog updateCatalog(AuthenticatedPrincipal principal, String projectId, String catalogId,
                                           CatalogInput input) {
        ProjectSummary project = writeAccess(principal, projectId);
        if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目录参数不能为空");
        String name = required(input.name(), 160, "目录名称");
        FunctionalCatalog existing = requireCatalog(projectId, catalogId);
        String scope = normalizeCatalogScope(input.scope(), existing.scope());
        if (!existing.scope().equals(scope)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目录所属 Tab 创建后不能修改");
        }
        String parentId = blankToNull(input.parentId());
        validateCatalogParent(projectId, scope, parentId, catalogId);
        try {
            if (catalogs.update(projectId, catalogId, parentId, name, input.sortOrder()) == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "目录不存在");
            }
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "同级目录名称已存在", exception);
        }
        audit.record(principal, project.organizationId(), projectId, "FUNCTIONAL_CATALOG_UPDATED", "FUNCTIONAL_CATALOG", catalogId,
                Map.of("name", name, "scope", scope));
        return catalogs.findById(projectId, catalogId).orElseThrow();
    }

    @Transactional
    public void deleteCatalog(AuthenticatedPrincipal principal, String projectId, String catalogId) {
        ProjectSummary project = writeAccess(principal, projectId);
        requireCatalog(projectId, catalogId);
        try {
            if (catalogs.delete(projectId, catalogId) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "目录不存在");
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "目录下仍有关联资产，不能删除", exception);
        }
        audit.record(principal, project.organizationId(), projectId, "FUNCTIONAL_CATALOG_DELETED", "FUNCTIONAL_CATALOG", catalogId,
                Map.of());
    }

    public List<FunctionalRequirement> listRequirements(AuthenticatedPrincipal principal, String projectId, String catalogId) {
        access.requireProjectRead(principal, projectId);
        if (catalogId != null && !catalogId.isBlank()) requireCatalog(projectId, catalogId, "REQUIREMENTS");
        return requirements.findByProject(projectId, blankToNull(catalogId));
    }

    @Transactional
    public FunctionalRequirement createRequirement(AuthenticatedPrincipal principal, String projectId, RequirementInput input) {
        ProjectSummary project = writeAccess(principal, projectId);
        validateRequirement(input);
        requireCatalog(projectId, input.catalogId(), "REQUIREMENTS");
        String id = UlidCreator.getUlid().toString();
        try {
            requirements.insert(id, project.organizationId(), projectId, input.catalogId(), normalizeCode(input.requirementCode(), "需求编号"),
                    required(input.title(), 200, "需求标题"), normalizePriority(input.priority()), normalizeRequirementStatus(input.status()),
                    text(input.description()), principal.userId());
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "需求编号已存在", exception);
        }
        audit.record(principal, project.organizationId(), projectId, "FUNCTIONAL_REQUIREMENT_CREATED", "FUNCTIONAL_REQUIREMENT", id,
                Map.of("title", input.title().trim()));
        return requirements.findById(projectId, id).orElseThrow();
    }

    @Transactional
    public FunctionalRequirement updateRequirement(AuthenticatedPrincipal principal, String projectId, String id, RequirementInput input) {
        ProjectSummary project = writeAccess(principal, projectId);
        validateRequirement(input);
        requireCatalog(projectId, input.catalogId(), "REQUIREMENTS");
        try {
            if (requirements.update(projectId, id, input.catalogId(), normalizeCode(input.requirementCode(), "需求编号"),
                    required(input.title(), 200, "需求标题"), normalizePriority(input.priority()), normalizeRequirementStatus(input.status()),
                    text(input.description()), principal.userId()) == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "需求不存在");
            }
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "需求编号已存在", exception);
        }
        audit.record(principal, project.organizationId(), projectId, "FUNCTIONAL_REQUIREMENT_UPDATED", "FUNCTIONAL_REQUIREMENT", id,
                Map.of());
        return requirements.findById(projectId, id).orElseThrow();
    }

    @Transactional
    public void deleteRequirement(AuthenticatedPrincipal principal, String projectId, String id) {
        ProjectSummary project = writeAccess(principal, projectId);
        if (requirements.delete(projectId, id) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "需求不存在");
        audit.record(principal, project.organizationId(), projectId, "FUNCTIONAL_REQUIREMENT_DELETED", "FUNCTIONAL_REQUIREMENT", id,
                Map.of());
    }

    public List<FunctionalCase> listCases(AuthenticatedPrincipal principal, String projectId, String catalogId) {
        access.requireProjectRead(principal, projectId);
        if (catalogId != null && !catalogId.isBlank()) requireCatalog(projectId, catalogId, "CASES");
        return cases.findByProject(projectId, blankToNull(catalogId));
    }

    @Transactional
    public FunctionalCase createCase(AuthenticatedPrincipal principal, String projectId, CaseInput input) {
        ProjectSummary project = writeAccess(principal, projectId);
        validateCase(input);
        requireCatalog(projectId, input.catalogId(), "CASES");
        if (input.requirementId() != null && !input.requirementId().isBlank()) requireRequirement(projectId, input.requirementId());
        String id = UlidCreator.getUlid().toString();
        try {
            cases.insert(id, project.organizationId(), projectId, input.catalogId(), blankToNull(input.requirementId()),
                    normalizeCode(input.caseCode(), "用例编号"), normalizeCaseType(input.caseType()), required(input.title(), 200, "用例标题"), normalizePriority(input.priority()),
                    normalizeCaseStatus(input.status()), text(input.precondition()), text(input.steps()), text(input.expectedResult()), principal.userId());
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用例编号已存在", exception);
        }
        audit.record(principal, project.organizationId(), projectId, "FUNCTIONAL_CASE_CREATED", "FUNCTIONAL_CASE", id,
                Map.of("title", input.title().trim()));
        return cases.findById(projectId, id).orElseThrow();
    }

    @Transactional
    public FunctionalCase updateCase(AuthenticatedPrincipal principal, String projectId, String id, CaseInput input) {
        ProjectSummary project = writeAccess(principal, projectId);
        validateCase(input);
        requireCatalog(projectId, input.catalogId(), "CASES");
        if (input.requirementId() != null && !input.requirementId().isBlank()) requireRequirement(projectId, input.requirementId());
        try {
            if (cases.update(projectId, id, input.catalogId(), blankToNull(input.requirementId()), normalizeCode(input.caseCode(), "用例编号"),
                    normalizeCaseType(input.caseType()), required(input.title(), 200, "用例标题"), normalizePriority(input.priority()), normalizeCaseStatus(input.status()),
                    text(input.precondition()), text(input.steps()), text(input.expectedResult()), principal.userId()) == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用例不存在");
            }
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用例编号已存在", exception);
        }
        audit.record(principal, project.organizationId(), projectId, "FUNCTIONAL_CASE_UPDATED", "FUNCTIONAL_CASE", id, Map.of());
        return cases.findById(projectId, id).orElseThrow();
    }

    @Transactional
    public void deleteCase(AuthenticatedPrincipal principal, String projectId, String id) {
        ProjectSummary project = writeAccess(principal, projectId);
        if (cases.delete(projectId, id) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用例不存在");
        audit.record(principal, project.organizationId(), projectId, "FUNCTIONAL_CASE_DELETED", "FUNCTIONAL_CASE", id, Map.of());
    }

    private ProjectSummary writeAccess(AuthenticatedPrincipal principal, String projectId) {
        return access.requireProjectParameterWrite(principal, projectId, WRITERS);
    }

    private FunctionalCatalog requireCatalog(String projectId, String id) {
        return catalogs.findById(projectId, id).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "目录不存在"));
    }

    private FunctionalCatalog requireCatalog(String projectId, String id, String scope) {
        FunctionalCatalog catalog = requireCatalog(projectId, id);
        if (!catalog.scope().equals(scope)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目录不属于当前 Tab");
        }
        return catalog;
    }

    private void validateCatalogParent(String projectId, String scope, String parentId, String catalogId) {
        if (parentId == null) return;
        if (parentId.equals(catalogId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目录不能设为自己的父级");
        }
        FunctionalCatalog parent = requireCatalog(projectId, parentId, scope);
        Set<String> visited = new java.util.HashSet<>();
        while (parent.parentId() != null) {
            if (!visited.add(parent.id()) || parent.parentId().equals(catalogId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目录父级不能形成循环");
            }
            parent = requireCatalog(projectId, parent.parentId(), scope);
        }
    }

    private FunctionalRequirement requireRequirement(String projectId, String id) {
        return requirements.findById(projectId, id).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "关联需求不存在"));
    }

    private void validateRequirement(RequirementInput input) {
        if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "需求参数不能为空");
        required(input.catalogId(), 26, "需求目录");
        required(input.requirementCode(), 40, "需求编号");
        required(input.title(), 200, "需求标题");
    }

    private void validateCase(CaseInput input) {
        if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用例参数不能为空");
        required(input.catalogId(), 26, "用例目录");
        required(input.caseCode(), 40, "用例编号");
        required(input.title(), 200, "用例标题");
        required(input.steps(), 20_000, "用例步骤");
        required(input.expectedResult(), 20_000, "预期结果");
    }

    private String normalizeCode(String value, String label) {
        String normalized = required(value, 40, label).toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z][A-Z0-9_-]{1,39}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "格式不合法");
        return normalized;
    }

    private String normalizePriority(String value) {
        String normalized = value == null ? "P1" : value.trim().toUpperCase(Locale.ROOT);
        if (!PRIORITIES.contains(normalized)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "优先级不合法");
        return normalized;
    }

    private String normalizeRequirementStatus(String value) {
        String normalized = value == null ? "DRAFT" : value.trim().toUpperCase(Locale.ROOT);
        if (!REQUIREMENT_STATUSES.contains(normalized)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "需求状态不合法");
        return normalized;
    }

    private String normalizeCaseStatus(String value) {
        String normalized = value == null ? "DRAFT" : value.trim().toUpperCase(Locale.ROOT);
        if (!CASE_STATUSES.contains(normalized)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用例状态不合法");
        return normalized;
    }

    private String normalizeCaseType(String value) {
        String normalized = value == null || value.isBlank() ? "FUNCTIONAL" : value.trim().toUpperCase(Locale.ROOT);
        if (!CASE_TYPES.contains(normalized)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用例类型不合法");
        return normalized;
    }

    private String normalizeCatalogScope(String value, String defaultScope) {
        String normalized = value == null || value.isBlank() ? defaultScope : value.trim().toUpperCase(Locale.ROOT);
        if (!CATALOG_SCOPES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目录所属 Tab 不合法");
        }
        return normalized;
    }

    private String required(String value, int max, String label) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "不能为空且不能超过" + max + "个字符");
        }
        return value.trim();
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record CatalogInput(String name, String parentId, int sortOrder, String scope) { }
    public record RequirementInput(String catalogId, String requirementCode, String title, String priority,
                                   String status, String description) { }
    public record CaseInput(String catalogId, String requirementId, String caseCode, String title, String priority,
                            String status, String caseType, String precondition, String steps, String expectedResult) { }
}
