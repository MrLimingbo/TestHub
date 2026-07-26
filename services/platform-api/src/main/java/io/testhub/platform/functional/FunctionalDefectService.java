package io.testhub.platform.functional;

import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ProjectSummary;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.net.URI;
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
public class FunctionalDefectService {
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private static final Set<String> SEVERITIES = Set.of("CRITICAL", "MAJOR", "MINOR", "TRIVIAL");
    private static final Set<String> STATUSES = Set.of("OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED");
    private static final Set<String> ASSET_TYPES = Set.of("REQUIREMENT", "CASE", "RUN_CASE");
    private final FunctionalDefectRepository defects;
    private final FunctionalRequirementRepository requirements;
    private final FunctionalCaseRepository cases;
    private final FunctionalTestExecutionRepository executions;
    private final AccessService access;
    private final AuditService audit;

    public FunctionalDefectService(FunctionalDefectRepository defects, FunctionalRequirementRepository requirements,
                                   FunctionalCaseRepository cases, FunctionalTestExecutionRepository executions,
                                   AccessService access, AuditService audit) {
        this.defects = defects;
        this.requirements = requirements;
        this.cases = cases;
        this.executions = executions;
        this.access = access;
        this.audit = audit;
    }

    public List<FunctionalDefect> list(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectRead(principal, projectId);
        return defects.findByProject(projectId);
    }

    @Transactional
    public FunctionalDefect create(AuthenticatedPrincipal principal, String projectId, DefectInput input) {
        ProjectSummary project = writeAccess(principal, projectId);
        ValidatedDefect defect = validate(input, true);
        String id = UlidCreator.getUlid().toString();
        try {
            defects.insert(id, project.organizationId(), projectId, defect.code(), defect.title(), defect.severity(), defect.status(),
                    defect.source(), defect.externalUrl(), defect.description(), principal.userId());
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "缺陷编号已存在", exception);
        }
        audit.record(principal, project.organizationId(), projectId, "FUNCTIONAL_DEFECT_CREATED", "FUNCTIONAL_DEFECT", id,
                Map.of("defectCode", defect.code(), "status", defect.status()));
        return requireDefect(projectId, id);
    }

    @Transactional
    public FunctionalDefect update(AuthenticatedPrincipal principal, String projectId, String defectId, DefectInput input) {
        ProjectSummary project = writeAccess(principal, projectId);
        FunctionalDefect existing = requireDefect(projectId, defectId);
        ValidatedDefect defect = validate(input, false);
        if (input.defectCode() != null && !input.defectCode().isBlank() && !existing.defectCode().equals(defect.code())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "已创建缺陷不能变更编号");
        }
        defects.update(projectId, defectId, defect.title(), defect.severity(), defect.status(), defect.source(), defect.externalUrl(),
                defect.description(), principal.userId());
        audit.record(principal, project.organizationId(), projectId, "FUNCTIONAL_DEFECT_UPDATED", "FUNCTIONAL_DEFECT", defectId,
                Map.of("status", defect.status()));
        return requireDefect(projectId, defectId);
    }

    @Transactional
    public void delete(AuthenticatedPrincipal principal, String projectId, String defectId) {
        ProjectSummary project = writeAccess(principal, projectId);
        if (defects.delete(projectId, defectId) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "缺陷不存在");
        audit.record(principal, project.organizationId(), projectId, "FUNCTIONAL_DEFECT_DELETED", "FUNCTIONAL_DEFECT", defectId, Map.of());
    }

    public List<FunctionalDefectLink> links(AuthenticatedPrincipal principal, String projectId, String defectId) {
        access.requireProjectRead(principal, projectId);
        requireDefect(projectId, defectId);
        return defects.findLinksByDefect(projectId, defectId);
    }

    @Transactional
    public FunctionalDefectLink link(AuthenticatedPrincipal principal, String projectId, String defectId, DefectLinkInput input) {
        ProjectSummary project = writeAccess(principal, projectId);
        requireDefect(projectId, defectId);
        if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "关联参数不能为空");
        String assetType = normalized(input.assetType(), ASSET_TYPES, "关联资产类型");
        if (input.assetId() == null || input.assetId().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "关联资产不能为空");
        requireAsset(projectId, assetType, input.assetId());
        String id = UlidCreator.getUlid().toString();
        try {
            defects.insertLink(id, projectId, defectId, assetType, input.assetId(), principal.userId());
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该资产已关联当前缺陷", exception);
        }
        audit.record(principal, project.organizationId(), projectId, "FUNCTIONAL_DEFECT_LINKED", "FUNCTIONAL_DEFECT", defectId,
                Map.of("assetType", assetType, "assetId", input.assetId()));
        return defects.findLinksByDefect(projectId, defectId).stream().filter(link -> link.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public void unlink(AuthenticatedPrincipal principal, String projectId, String defectId, String linkId) {
        ProjectSummary project = writeAccess(principal, projectId);
        requireDefect(projectId, defectId);
        if (defects.deleteLink(projectId, defectId, linkId) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "缺陷关联不存在");
        audit.record(principal, project.organizationId(), projectId, "FUNCTIONAL_DEFECT_UNLINKED", "FUNCTIONAL_DEFECT", defectId,
                Map.of("linkId", linkId));
    }

    private ProjectSummary writeAccess(AuthenticatedPrincipal principal, String projectId) {
        return access.requireProjectParameterWrite(principal, projectId, WRITERS);
    }

    private FunctionalDefect requireDefect(String projectId, String defectId) {
        return defects.findById(projectId, defectId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "缺陷不存在"));
    }

    private void requireAsset(String projectId, String assetType, String assetId) {
        boolean exists = switch (assetType) {
            case "REQUIREMENT" -> requirements.findById(projectId, assetId).isPresent();
            case "CASE" -> cases.findById(projectId, assetId).isPresent();
            case "RUN_CASE" -> executions.findRunCase(projectId, assetId).isPresent();
            default -> false;
        };
        if (!exists) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "关联资产不存在或不属于当前项目");
    }

    private ValidatedDefect validate(DefectInput input, boolean creating) {
        if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺陷参数不能为空");
        String code = input.defectCode() == null || input.defectCode().isBlank() ? "" : code(input.defectCode());
        if (creating && code.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺陷编号不能为空");
        return new ValidatedDefect(code, required(input.title(), 200, "缺陷标题"), normalizedDefault(input.severity(), "MAJOR", SEVERITIES, "严重程度"),
                normalizedDefault(input.status(), "OPEN", STATUSES, "缺陷状态"), source(input.source()), externalUrl(input.externalUrl()), text(input.description()));
    }

    private String code(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z][A-Z0-9_-]{1,39}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺陷编号格式不合法");
        return normalized;
    }

    private String source(String value) {
        String normalized = value == null || value.isBlank() ? "TESTHUB" : value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z][A-Z0-9_-]{1,23}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺陷来源格式不合法");
        return normalized;
    }

    private String externalUrl(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.trim().length() > 1000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "外部链接不能超过1000个字符");
        try {
            URI uri = URI.create(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "外部链接必须为 HTTP 或 HTTPS 地址", exception);
        }
        return value.trim();
    }

    private String normalized(String value, Set<String> allowed, String label) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "不合法");
        return normalized;
    }

    private String normalizedDefault(String value, String defaultValue, Set<String> allowed, String label) {
        return value == null || value.isBlank() ? defaultValue : normalized(value, allowed, label);
    }

    private String required(String value, int max, String label) {
        if (value == null || value.isBlank() || value.trim().length() > max) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "不能为空且不能超过" + max + "个字符");
        return value.trim();
    }

    private String text(String value) { return value == null ? "" : value.trim(); }

    public record DefectInput(String defectCode, String title, String severity, String status, String source, String externalUrl, String description) { }
    public record DefectLinkInput(String assetType, String assetId) { }
    private record ValidatedDefect(String code, String title, String severity, String status, String source, String externalUrl, String description) { }
}
