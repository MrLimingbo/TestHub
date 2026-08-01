package io.testhub.platform.functional;

import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ProjectSummary;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
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
    private static final Set<String> PRIORITIES = Set.of("P0", "P1", "P2", "P3");
    private static final Set<String> STATUSES = Set.of("OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED");
    private static final Set<String> BUG_TYPES = Set.of("CODE_ERROR", "CONFIGURATION", "INSTALLATION", "SECURITY",
            "PERFORMANCE", "STANDARD", "TEST_SCRIPT", "DESIGN_DEFECT", "OTHER");
    private static final Set<String> BROWSERS = Set.of("CHROME", "EDGE", "IE", "SAFARI", "360_BROWSER", "QQ_BROWSER",
            "OTHER");
    private static final Set<String> OPERATING_SYSTEMS = Set.of("WINDOWS", "MAC_OS", "LINUX", "UBUNTU", "OTHER");
    private static final Set<String> RESOLUTIONS = Set.of("FIXED", "DUPLICATE", "BY_DESIGN", "CANNOT_REPRODUCE",
            "POSTPONED", "EXTERNAL");
    private static final Set<String> ASSET_TYPES = Set.of("REQUIREMENT", "CASE", "RUN_CASE");
    private final FunctionalDefectRepository defects;
    private final FunctionalCatalogRepository catalogs;
    private final FunctionalRequirementRepository requirements;
    private final FunctionalCaseRepository cases;
    private final FunctionalTestExecutionRepository executions;
    private final AccessService access;
    private final AuditService audit;

    public FunctionalDefectService(FunctionalDefectRepository defects, FunctionalCatalogRepository catalogs,
                                   FunctionalRequirementRepository requirements, FunctionalCaseRepository cases,
                                   FunctionalTestExecutionRepository executions, AccessService access, AuditService audit) {
        this.defects = defects;
        this.catalogs = catalogs;
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
        String id = UlidCreator.getUlid().toString();
        ValidatedDefect defect = validate(principal, projectId, input, null);
        String defectCode = defect.code() == null ? "BUG_" + id.substring(id.length() - 12) : defect.code();
        Lifecycle lifecycle = lifecycle(null, defect.status(), defect.resolution(), principal.userId());
        try {
            defects.insert(id, project.organizationId(), projectId, defect.catalogId(), defectCode, defect.title(),
                    defect.dueDate(), defect.bugType(), defect.keywords(), defect.browsers(),
                    defect.operatingSystems(), defect.ccUserIds(), defect.severity(), defect.priority(),
                    defect.status(), defect.confirmed(), defect.assigneeId(),
                    defect.assigneeId() == null ? null : principal.userId(), lifecycle.resolution(),
                    lifecycle.resolvedBy(), lifecycle.resolvedAt(), lifecycle.closedBy(),
                    lifecycle.closedAt(), defect.source(), defect.externalUrl(), defect.description(), principal.userId());
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "缺陷编号已存在", exception);
        }
        audit.record(principal, project.organizationId(), projectId, "FUNCTIONAL_DEFECT_CREATED", "FUNCTIONAL_DEFECT", id,
                Map.of("defectCode", defectCode, "status", defect.status()));
        return requireDefect(projectId, id);
    }

    @Transactional
    public FunctionalDefect update(AuthenticatedPrincipal principal, String projectId, String defectId, DefectInput input) {
        ProjectSummary project = writeAccess(principal, projectId);
        FunctionalDefect existing = requireDefect(projectId, defectId);
        ValidatedDefect defect = validate(principal, projectId, input, existing);
        if (input.defectCode() != null && !input.defectCode().isBlank() && !existing.defectCode().equals(defect.code())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "已创建缺陷不能变更编号");
        }
        Lifecycle lifecycle = lifecycle(existing, defect.status(), defect.resolution(), principal.userId());
        String assignedBy = java.util.Objects.equals(existing.assigneeId(), defect.assigneeId())
                ? existing.assignedBy() : defect.assigneeId() == null ? null : principal.userId();
        defects.update(projectId, defectId, defect.catalogId(), defect.title(), defect.dueDate(), defect.bugType(),
                defect.keywords(), defect.browsers(), defect.operatingSystems(), defect.ccUserIds(),
                defect.severity(), defect.priority(), defect.status(), defect.confirmed(), defect.assigneeId(),
                assignedBy, lifecycle.resolution(),
                lifecycle.resolvedBy(), lifecycle.resolvedAt(), lifecycle.closedBy(), lifecycle.closedAt(),
                defect.source(), defect.externalUrl(), defect.description(), principal.userId());
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

    private ValidatedDefect validate(AuthenticatedPrincipal principal, String projectId, DefectInput input,
                                     FunctionalDefect existing) {
        if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺陷参数不能为空");
        String code = input.defectCode() == null || input.defectCode().isBlank() ? null : code(input.defectCode());
        String catalogId = input.catalogId() == null && existing != null ? existing.catalogId() : blankToNull(input.catalogId());
        requireDeliveryCatalog(projectId, catalogId);
        String assigneeId = input.assigneeId() == null && existing != null
                ? existing.assigneeId() : blankToNull(input.assigneeId());
        requireProjectMember(principal, projectId, assigneeId);
        List<String> ccUserIds = input.ccUserIds() == null && existing != null
                ? existing.ccUserIds() : memberSelections(principal, projectId, input.ccUserIds());
        String status = normalizedDefault(input.status(), existing == null ? "OPEN" : existing.status(),
                STATUSES, "缺陷状态");
        String resolution = input.resolution() == null && existing != null ? existing.resolution()
                : normalizedNullable(input.resolution(), RESOLUTIONS, "解决方案");
        return new ValidatedDefect(catalogId, code, required(input.title(), 200, "缺陷标题"),
                input.dueDate() == null && existing != null ? existing.dueDate() : input.dueDate(),
                normalizedDefault(input.bugType(), existing == null ? "CODE_ERROR" : existing.bugType(),
                        BUG_TYPES, "Bug类型"),
                limitedText(input.keywords() == null && existing != null ? existing.keywords() : input.keywords(),
                        500, "关键词"),
                selections(input.browsers(), existing == null ? List.of() : existing.browsers(),
                        BROWSERS, "浏览器"),
                selections(input.operatingSystems(), existing == null ? List.of() : existing.operatingSystems(),
                        OPERATING_SYSTEMS, "操作系统"),
                ccUserIds,
                normalizedDefault(input.severity(), existing == null ? "MAJOR" : existing.severity(),
                        SEVERITIES, "严重程度"),
                normalizedDefault(input.priority(), existing == null ? "P1" : existing.priority(),
                        PRIORITIES, "优先级"),
                status, input.confirmed() == null ? existing != null && existing.confirmed() : input.confirmed(),
                assigneeId, resolution, source(input.source() == null && existing != null ? existing.source() : input.source()),
                externalUrl(input.externalUrl() == null && existing != null ? existing.externalUrl() : input.externalUrl()),
                requiredRichText(input.description() == null && existing != null
                        ? existing.description() : input.description()));
    }

    private Lifecycle lifecycle(FunctionalDefect existing, String status, String requestedResolution, String actorId) {
        if (!Set.of("RESOLVED", "CLOSED").contains(status)) {
            return new Lifecycle(null, null, null, null, null);
        }
        Instant now = Instant.now();
        String resolution = requestedResolution == null ? "FIXED" : requestedResolution;
        String resolvedBy = existing != null && existing.resolvedBy() != null ? existing.resolvedBy() : actorId;
        Instant resolvedAt = existing != null && existing.resolvedAt() != null ? existing.resolvedAt() : now;
        if (!"CLOSED".equals(status)) {
            return new Lifecycle(resolution, resolvedBy, resolvedAt, null, null);
        }
        String closedBy = existing != null && existing.closedBy() != null ? existing.closedBy() : actorId;
        Instant closedAt = existing != null && existing.closedAt() != null ? existing.closedAt() : now;
        return new Lifecycle(resolution, resolvedBy, resolvedAt, closedBy, closedAt);
    }

    private void requireDeliveryCatalog(String projectId, String catalogId) {
        if (catalogId == null) return;
        FunctionalCatalog catalog = catalogs.findById(projectId, catalogId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "所属目录不存在"));
        if (!"DELIVERY".equals(catalog.scope())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目录不属于缺陷与交换 Tab");
        }
    }

    private void requireProjectMember(AuthenticatedPrincipal principal, String projectId, String userId) {
        if (userId == null || userId.equals(principal.userId())) return;
        boolean exists = access.members(principal, projectId).stream().anyMatch(member -> member.userId().equals(userId));
        if (!exists) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "指派人不是当前项目成员");
    }

    private List<String> memberSelections(AuthenticatedPrincipal principal, String projectId, List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        List<String> selected = values.stream().map(this::blankToNull).filter(java.util.Objects::nonNull).distinct().toList();
        Set<String> memberIds = access.members(principal, projectId).stream()
                .map(member -> member.userId()).collect(java.util.stream.Collectors.toSet());
        memberIds.add(principal.userId());
        if (selected.stream().anyMatch(userId -> !memberIds.contains(userId))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "抄送人不是当前项目成员");
        }
        return selected;
    }

    private List<String> selections(List<String> values, List<String> existing, Set<String> allowed, String label) {
        if (values == null) return existing;
        return values.stream().map(value -> normalized(value, allowed, label)).distinct().toList();
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

    private String normalizedNullable(String value, Set<String> allowed, String label) {
        return value == null || value.isBlank() ? null : normalized(value, allowed, label);
    }

    private String required(String value, int max, String label) {
        if (value == null || value.isBlank() || value.trim().length() > max) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "不能为空且不能超过" + max + "个字符");
        return value.trim();
    }

    private String limitedText(String value, int max, String label) {
        String normalized = text(value);
        if (normalized.length() > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "不能超过" + max + "个字符");
        }
        return normalized;
    }

    private String requiredRichText(String value) {
        String normalized = value == null ? "" : value.trim();
        String visibleText = normalized.replaceAll("(?is)<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&#160;", " ").trim();
        if (normalized.length() > 20_000 || visibleText.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "重现步骤不能为空且不能超过20000个字符");
        }
        return normalized;
    }

    private String text(String value) { return value == null ? "" : value.trim(); }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    public record DefectInput(String catalogId, String defectCode, String title, LocalDate dueDate, String bugType,
                              String keywords, List<String> browsers, List<String> operatingSystems,
                              List<String> ccUserIds, String severity, String priority, String status,
                              Boolean confirmed, String assigneeId, String resolution, String source,
                              String externalUrl, String description) { }
    public record DefectLinkInput(String assetType, String assetId) { }
    private record ValidatedDefect(String catalogId, String code, String title, LocalDate dueDate, String bugType,
                                   String keywords, List<String> browsers, List<String> operatingSystems,
                                   List<String> ccUserIds, String severity, String priority, String status,
                                   boolean confirmed, String assigneeId, String resolution, String source,
                                   String externalUrl, String description) { }
    private record Lifecycle(String resolution, String resolvedBy, Instant resolvedAt, String closedBy,
                             Instant closedAt) { }
}
