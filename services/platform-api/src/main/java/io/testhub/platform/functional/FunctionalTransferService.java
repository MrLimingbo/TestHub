package io.testhub.platform.functional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Portable definitions with opt-in files and execution snapshots; source users and credentials are excluded. */
@Service
public class FunctionalTransferService {
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private final FunctionalAssetService assets;
    private final FunctionalTestExecutionService executions;
    private final FunctionalTransferGovernance governance;
    private final FunctionalTransferDefects defects;
    private final FunctionalTransferFiles files;
    private final FunctionalTransferHistory history;
    private final FunctionalTransferVersions projectVersions;
    private final AccessService access;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    public FunctionalTransferService(FunctionalAssetService assets, FunctionalTestExecutionService executions, FunctionalTransferGovernance governance,
                                     AccessService access, AuditService audit, JdbcClient jdbc, ObjectMapper json, FunctionalTransferDefects defects, FunctionalTransferFiles files, FunctionalTransferHistory history, FunctionalTransferVersions projectVersions) {
        this.assets = assets; this.executions = executions; this.governance = governance; this.defects = defects; this.files = files; this.history = history; this.projectVersions = projectVersions; this.access = access; this.audit = audit; this.jdbc = jdbc; this.json = json;
    }
    public Map<String, Object> capabilities(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectParameterWrite(principal, projectId, WRITERS);
        return Map.of("version", 1, "versions", List.of(1, 2, 3, 4, 5, 6, 7, 8), "maxBytes", 8 * 1024 * 1024, "scope", "FUNCTIONAL_DEFINITIONS", "fileSnapshotMaxBytes", 32*1024*1024, "maxFileBytes", FunctionalTransferFiles.MAX_BYTES, "maxFiles", 100);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot export(AuthenticatedPrincipal principal, String projectId) {
        return export(principal, projectId, 1);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot export(AuthenticatedPrincipal principal, String projectId, int version) {
        return export(principal, projectId, version, version >= 2);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot export(AuthenticatedPrincipal principal, String projectId, int version, boolean includePlans) {
        return export(principal, projectId, version, includePlans, version >= 3);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot export(AuthenticatedPrincipal principal, String projectId, int version, boolean includePlans, boolean includeGovernance) {
        return export(principal, projectId, version, includePlans, includeGovernance, version >= 4);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot export(AuthenticatedPrincipal principal, String projectId, int version, boolean includePlans, boolean includeGovernance, boolean includeDefects) {
        return export(principal, projectId, version, includePlans, includeGovernance, includeDefects, version >= 5, version >= 6);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot export(AuthenticatedPrincipal principal, String projectId, int version, boolean includePlans, boolean includeGovernance, boolean includeDefects, boolean includeFiles, boolean includeHistory) {
        return export(principal, projectId, version, includePlans, includeGovernance, includeDefects, includeFiles, includeHistory, version >= 7);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot export(AuthenticatedPrincipal principal, String projectId, int version, boolean includePlans, boolean includeGovernance, boolean includeDefects, boolean includeFiles, boolean includeHistory, boolean includeProjectVersions) {
        return export(principal, projectId, version, includePlans, includeGovernance, includeDefects, includeFiles, includeHistory, includeProjectVersions, version >= 8);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot export(AuthenticatedPrincipal principal, String projectId, int version, boolean includePlans, boolean includeGovernance, boolean includeDefects, boolean includeFiles, boolean includeHistory, boolean includeProjectVersions, boolean includeGovernanceCatalogs) {
        access.requireProjectRead(principal, projectId);
        if ((includeGovernanceCatalogs && version != 8) || (version == 8 && !includeGovernanceCatalogs)) throw invalid("治理目录的迁移版本或范围无效。");
        if ((includeProjectVersions && version < 7) || (version == 7 && !includeProjectVersions)) throw invalid("项目版本的迁移版本或范围无效。");
        if ((includeHistory && (version < 6 || !includePlans)) || (version == 6 && !includeHistory)
                || (includeFiles && version < 5) || (version == 5 && !includeFiles)) throw invalid("执行历史需同时选择测试计划，迁移版本或范围无效。");
        if (version < 1 || version > 8 || (version == 1 && includePlans) || (version == 2 && !includePlans)
                || (version < 3 && includeGovernance) || (version == 3 && !includeGovernance) || (version < 4 && includeDefects) || (version == 4 && !includeDefects)) throw invalid("迁移资料版本或范围不支持。");
        List<Catalog> catalogs = new ArrayList<>();
        var scopes = new ArrayList<>(List.of("REQUIREMENTS", "CASES"));
        if (includePlans) scopes.add("PLANS");
        if (includeDefects) scopes.add("DELIVERY");
        if (includeGovernanceCatalogs) scopes.add("GOVERNANCE");
        for (String scope : scopes) assets.listCatalogs(principal, projectId, scope).forEach(item ->
                catalogs.add(new Catalog(item.id(), item.parentId(), item.scope(), item.name(), item.sortOrder())));
        var requirements = assets.listRequirements(principal, projectId, null).stream().map(item -> new Requirement(item.id(), item.catalogId(),
                item.requirementCode(), item.title(), item.priority(), item.status(), item.description())).toList();
        var cases = assets.listCases(principal, projectId, null).stream().map(item -> new Case(item.id(), item.catalogId(), item.requirementId(),
                item.caseCode(), item.title(), item.priority(), item.status(), item.caseType(), item.precondition(), item.steps(), item.expectedResult())).toList();
        List<Plan> plans = !includePlans ? null : executions.listPlans(principal, projectId, null).stream().map(item ->
                new Plan(item.id(), item.catalogId(), item.planCode(), item.name(), item.status(), item.description(),
                        executions.listPlanCases(principal, projectId, item.id()).stream().map(FunctionalTestPlanCase::caseId).toList())).toList();
        var snapshot = new Snapshot(version, catalogs, requirements, cases, plans);
        if (includeGovernance) snapshot = new Snapshot(version, catalogs, requirements, cases, plans,
                governance.export(principal, projectId, assetIds(snapshot)));
        if (includeDefects) snapshot = new Snapshot(version, catalogs, requirements, cases, plans, snapshot.governance(), defects.export(principal, projectId, includeHistory));
        if (includeFiles) snapshot = new Snapshot(version,catalogs,requirements,cases,plans,snapshot.governance(),snapshot.defects(),files.export(principal,projectId));
        if (includeHistory) snapshot = new Snapshot(version,catalogs,requirements,cases,plans,snapshot.governance(),snapshot.defects(),snapshot.files(),history.export(projectId));
        if (includeProjectVersions) snapshot = new Snapshot(version,catalogs,requirements,cases,plans,snapshot.governance(),snapshot.defects(),snapshot.files(),snapshot.history(),projectVersions.export(principal,projectId));
        validate(snapshot); encoded(snapshot);
        return snapshot;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt transfer(AuthenticatedPrincipal principal, String projectId, ImportRequest request) {
        var project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
        assets.lockProject(projectId);
        if (!principal.userId().equals(request.expectedUserId())) throw conflict("登录账号已变化，请重新预览迁移。");
        var snapshot = request.snapshot(); validate(snapshot);
        String digest;
        try { digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded(snapshot))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
        var existing = jdbc.sql("SELECT payload_sha256,result_json FROM functional_transfer_receipt WHERE project_id=:project AND requested_by=:user AND request_key=:key")
                .param("project", projectId).param("user", principal.userId()).param("key", request.requestKey())
                .query((rs, row) -> new String[]{rs.getString(1), rs.getString(2)}).optional();
        if (existing.isPresent()) {
            if (!existing.get()[0].equals(digest)) throw conflict("该迁移请求已用于其他数据，请重新预览。");
            try { return json.readValue(existing.get()[1], Receipt.class); }
            catch (Exception error) { throw new IllegalStateException("迁移记录读取失败", error); }
        }
        var codes = new HashSet<String>();
        assets.listRequirements(principal, projectId, null).forEach(item -> codes.add(item.requirementCode().toUpperCase(Locale.ROOT)));
        for (var item : snapshot.requirements()) if (codes.contains(item.code().toUpperCase(Locale.ROOT))) throw conflict("目标项目已有需求编号：" + item.code());
        codes.clear(); assets.listCases(principal, projectId, null).forEach(item -> codes.add(item.caseCode().toUpperCase(Locale.ROOT)));
        for (var item : snapshot.cases()) if (codes.contains(item.code().toUpperCase(Locale.ROOT))) throw conflict("目标项目已有用例编号：" + item.code());
        if (snapshot.plans() != null) {
            codes.clear(); executions.listPlans(principal, projectId, null).forEach(item -> codes.add(item.planCode().toUpperCase(Locale.ROOT)));
            for (var item : snapshot.plans()) if (codes.contains(item.code().toUpperCase(Locale.ROOT))) throw conflict("目标项目已有计划编号：" + item.code());
        }
        if (snapshot.governance() != null) governance.checkConflicts(principal, projectId, snapshot.governance());
        if (snapshot.projectVersions() != null) projectVersions.checkConflicts(principal, projectId, snapshot.projectVersions());
        if (snapshot.defects() != null) defects.checkConflicts(principal, projectId, snapshot.defects());
        String id = UlidCreator.getUlid().toString();
        try {
            jdbc.sql("INSERT INTO functional_transfer_receipt(id,organization_id,project_id,requested_by,request_key,payload_sha256) VALUES(:id,:org,:project,:user,:key,:digest)")
                    .param("id", id).param("org", project.organizationId()).param("project", projectId).param("user", principal.userId())
                    .param("key", request.requestKey()).param("digest", digest).update();
        } catch (org.springframework.dao.DuplicateKeyException error) { throw conflict("相同迁移正在提交，请使用原请求重试。"); }
        if (snapshot.files() != null) snapshot = files.rewriteSnapshot(snapshot, files.transfer(principal, projectId, snapshot.files()));
        Map<String, String> catalogIds = new HashMap<>(), requirementIds = new HashMap<>(), caseIds = new HashMap<>(), roots = new HashMap<>();
        for (var item : orderedCatalogs(snapshot)) {
            String root = roots.computeIfAbsent(item.scope(), scope -> assets.createCatalog(principal, projectId,
                    new FunctionalAssetService.CatalogInput("本地迁入-" + id, null, 0, scope)).id());
            var created = assets.createCatalog(principal, projectId, new FunctionalAssetService.CatalogInput(item.name(),
                    item.parentId() == null ? root : catalogIds.get(item.parentId()), item.sortOrder(), item.scope()));
            catalogIds.put(item.id(), created.id());
        }
        for (var item : snapshot.requirements()) {
            var created = assets.createRequirement(principal, projectId, new FunctionalAssetService.RequirementInput(catalogIds.get(item.catalogId()),
                    item.code(), item.title(), item.priority(), item.status(), item.description()));
            requirementIds.put(item.id(), created.id());
        }
        for (var item : snapshot.cases()) {
            var created = assets.createCase(principal, projectId, new FunctionalAssetService.CaseInput(catalogIds.get(item.catalogId()),
                item.requirementId() == null ? null : requirementIds.get(item.requirementId()), item.code(), item.title(), item.priority(), item.status(),
                item.caseType(), item.precondition(), item.steps(), item.expectedResult()));
            caseIds.put(item.id(), created.id());
        }
        Map<String, String> mappedAssets = new HashMap<>(requirementIds); mappedAssets.putAll(caseIds);
        if (snapshot.plans() != null) for (var item : snapshot.plans()) {
            var created = executions.createPlan(principal, projectId,
                new FunctionalTestExecutionService.PlanInput(item.catalogId() == null ? null : catalogIds.get(item.catalogId()),
                        item.code(), item.name(), item.status(), item.description(), item.caseIds().stream().map(caseIds::get).toList()));
            mappedAssets.put(item.id(), created.id());
        }
        var governanceCounts = snapshot.governance() == null ? null : governance.transfer(principal, projectId, snapshot.governance(), mappedAssets);
        var historyCounts = snapshot.history() == null ? null : history.transfer(project.organizationId(), projectId, principal.userId(), snapshot.history(), mappedAssets);
        Integer versionCount = snapshot.projectVersions() == null ? null : projectVersions.transfer(principal, projectId, snapshot.projectVersions());
        var defectCounts = snapshot.defects() == null ? null : defects.transfer(principal, projectId, snapshot.defects(), catalogIds, mappedAssets);
        var fileCounts = snapshot.files()==null?null:new FunctionalTransferFiles.Counts(snapshot.files().items().size(),snapshot.files().items().stream().mapToLong(FunctionalTransferFiles.File::size).sum());
        var receipt = new Receipt(id, snapshot.catalogs().size(), snapshot.requirements().size(), snapshot.cases().size(), roots,
                snapshot.plans() == null ? null : snapshot.plans().size(), governanceCounts, defectCounts, fileCounts, historyCounts, versionCount, snapshot.version() == 8 ? governanceCatalogCount(snapshot) : null);
        try {
            jdbc.sql("UPDATE functional_transfer_receipt SET result_json=:result WHERE id=:id").param("result", json.writeValueAsString(receipt)).param("id", id).update();
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException(error); }
        audit.record(principal, project.organizationId(), projectId, "FUNCTIONAL_ASSETS_TRANSFERRED", "FUNCTIONAL_TRANSFER", id,
                Map.of("catalogs", receipt.catalogs(), "requirements", receipt.requirements(), "cases", receipt.cases(),
                        "plans", receipt.plans() == null ? 0 : receipt.plans(), "tags", governanceCounts == null ? 0 : governanceCounts.tags(),
                        "fields", governanceCounts == null ? 0 : governanceCounts.fields(), "tagBindings", governanceCounts == null ? 0 : governanceCounts.bindings(), "defects", defectCounts == null ? 0 : defectCounts.items(),
                        "defectLinks", defectCounts == null ? 0 : defectCounts.links(), "executionRuns", historyCounts == null ? 0 : historyCounts.runs()));
        return receipt;
    }

    private byte[] encoded(Snapshot snapshot) {
        try {
            byte[] bytes = json.writeValueAsBytes(snapshot);
            int limit=snapshot.files()!=null?32:8;
            if (bytes.length > limit * 1024 * 1024) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "单次迁移资料不能超过 " + limit + " MiB。");
            return bytes;
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException(error); }
    }
    private void validate(Snapshot snapshot) {
        if (snapshot == null || snapshot.version() < 1 || snapshot.version() > 8 || snapshot.catalogs() == null || snapshot.requirements() == null || snapshot.cases() == null
                || (snapshot.version() == 1 && snapshot.plans() != null) || (snapshot.version() == 2 && snapshot.plans() == null)
                || (snapshot.version() < 3 && snapshot.governance() != null) || (snapshot.version() == 3 && snapshot.governance() == null)
                || (snapshot.version() < 4 && snapshot.defects() != null) || (snapshot.version() == 4 && snapshot.defects() == null)
                || (snapshot.version() < 5 && snapshot.files() != null) || (snapshot.version() == 5 && snapshot.files() == null)
                || (snapshot.version() < 6 && snapshot.history() != null) || (snapshot.version() == 6 && snapshot.history() == null)
                || (snapshot.history() != null && snapshot.plans() == null)
                || (snapshot.version() < 7 && snapshot.projectVersions() != null) || (snapshot.version() == 7 && snapshot.projectVersions() == null))
            throw invalid("迁移资料格式不兼容。");
        var plans = snapshot.plans() == null ? List.<Plan>of() : snapshot.plans();
        int assetCount = snapshot.requirements().size() + snapshot.cases().size() + plans.size();
        if (snapshot.catalogs().size() > 5000 || assetCount > 5000) throw invalid("单次最多迁移合计 5000 条需求、用例和计划，以及 5000 个目录。");
        var scopes = new HashSet<>(List.of("REQUIREMENTS", "CASES"));
        if (snapshot.plans() != null) scopes.add("PLANS");
        if (snapshot.defects() != null) scopes.add("DELIVERY");
        if (snapshot.version() == 8) scopes.add("GOVERNANCE");
        var catalogs = new HashMap<String, Catalog>(); var ids = new HashSet<String>();
        for (var item : snapshot.catalogs()) {
            if (item == null || item.id() == null || !ids.add(item.id()) || item.scope() == null || !scopes.contains(item.scope())) throw invalid("目录标识或范围无效。");
            catalogs.put(item.id(), item);
        }
        orderedCatalogs(snapshot);
        var requirements = new HashSet<String>(); var codes = new HashSet<String>();
        for (var item : snapshot.requirements()) {
            if (item == null || item.id() == null || !ids.add(item.id()) || item.code() == null || !codes.add(item.code().toUpperCase(Locale.ROOT))) throw invalid("需求标识或编号重复。");
            requireCatalog(catalogs, item.catalogId(), "REQUIREMENTS"); requirements.add(item.id());
        }
        codes.clear(); var cases = new HashSet<String>();
        for (var item : snapshot.cases()) {
            if (item == null || item.id() == null || !ids.add(item.id()) || item.code() == null || !codes.add(item.code().toUpperCase(Locale.ROOT))) throw invalid("用例标识或编号重复。");
            requireCatalog(catalogs, item.catalogId(), "CASES");
            if (item.requirementId() != null && !requirements.contains(item.requirementId())) throw invalid("关联需求不在本次迁移资料中。");
            cases.add(item.id());
        }
        codes.clear(); long associations = 0;
        for (var item : plans) {
            if (item == null || item.id() == null || !ids.add(item.id()) || item.code() == null || !codes.add(item.code().toUpperCase(Locale.ROOT))) throw invalid("计划标识或编号重复。");
            if (item.catalogId() != null) requireCatalog(catalogs, item.catalogId(), "PLANS");
            if (item.caseIds() == null || item.caseIds().size() > 5000 || item.caseIds().size() != new HashSet<>(item.caseIds()).size()
                    || item.caseIds().stream().anyMatch(id -> id == null || !cases.contains(id))) throw invalid("计划关联的用例重复或不在本次迁移资料中。");
            associations += item.caseIds().size();
            if (associations > 50000) throw invalid("单次最多迁移 50000 条计划用例关联。");
        }
        if (snapshot.governance() != null) {
            governance.validate(snapshot.governance(), assetIds(snapshot), ids);
            assetCount += snapshot.governance().tags().size() + snapshot.governance().fields().size();
        }
        if (snapshot.history() != null) history.validate(snapshot.history(), snapshot, ids);
        if (snapshot.defects() != null) {
            defects.validate(snapshot.defects(), snapshot, ids);
            assetCount += snapshot.defects().items().size();
        }
        if (snapshot.projectVersions() != null) {
            projectVersions.validate(snapshot.projectVersions(), ids);
            assetCount += snapshot.projectVersions().items().size();
        }
        if (snapshot.files() != null) {
            files.validate(snapshot.files(), ids);
            Map<String,String> paths=new HashMap<>();
            snapshot.files().items().forEach(item->paths.put(item.id(), "/api/v1/projects/"+snapshot.files().projectId()+"/files/"+item.id()+"/download"));
            files.rewriteSnapshot(snapshot,paths); // Reject missing same-project references before writing anything.
        }
        if (assetCount == 0 && (snapshot.files()==null || snapshot.files().items().isEmpty()) && (snapshot.version() < 8 || snapshot.catalogs().isEmpty())) throw invalid("所选项目没有可迁移的资料。");
        if (assetCount > 5000) throw invalid("需求、用例、计划、标签、字段、缺陷和项目版本合计不能超过 5000 条。");
    }
    private int governanceCatalogCount(Snapshot snapshot) { return (int) snapshot.catalogs().stream().filter(item -> "GOVERNANCE".equals(item.scope())).count(); }
    private Map<String, List<String>> assetIds(Snapshot snapshot) {
        Map<String, List<String>> ids = new LinkedHashMap<>();
        ids.put("REQUIREMENT", snapshot.requirements().stream().map(Requirement::id).toList());
        ids.put("CASE", snapshot.cases().stream().map(Case::id).toList());
        if (snapshot.plans() != null) ids.put("PLAN", snapshot.plans().stream().map(Plan::id).toList());
        return ids;
    }
    private List<Catalog> orderedCatalogs(Snapshot snapshot) {
        Map<String, Catalog> pending = new LinkedHashMap<>(); snapshot.catalogs().forEach(item -> pending.put(item.id(), item));
        List<Catalog> ordered = new ArrayList<>(); Map<String, Catalog> added = new HashMap<>();
        for (int depth = 0; !pending.isEmpty() && depth < 64; depth++) {
            List<Catalog> next = pending.values().stream().filter(item -> item.parentId() == null || added.containsKey(item.parentId())).toList();
            if (next.isEmpty()) throw invalid("目录层级存在循环或缺失父目录。");
            for (var item : next) {
                if (item.parentId() != null && !added.get(item.parentId()).scope().equals(item.scope())) throw invalid("父子目录范围不一致。");
                ordered.add(item); added.put(item.id(), item); pending.remove(item.id());
            }
        }
        if (!pending.isEmpty()) throw invalid("目录层级不能超过 64 层。");
        return ordered;
    }
    private void requireCatalog(Map<String, Catalog> catalogs, String id, String scope) {
        if (!catalogs.containsKey(id) || !scope.equals(catalogs.get(id).scope())) throw invalid("资料的目录关联不在本次迁移范围内。");
    }
    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }

    public record Snapshot(int version, @NotNull @Size(max=5000) List<@NotNull @Valid Catalog> catalogs,
                           @NotNull @Size(max=5000) List<@NotNull @Valid Requirement> requirements, @NotNull @Size(max=5000) List<@NotNull @Valid Case> cases,
                           @JsonInclude(JsonInclude.Include.NON_NULL) @Size(max=5000) List<@NotNull @Valid Plan> plans,
                           @JsonInclude(JsonInclude.Include.NON_NULL) @Valid FunctionalTransferGovernance.Definitions governance,
                           @JsonInclude(JsonInclude.Include.NON_NULL) @Valid FunctionalTransferDefects.Definitions defects,
                           @JsonInclude(JsonInclude.Include.NON_NULL) @Valid FunctionalTransferFiles.Definitions files,
                           @JsonInclude(JsonInclude.Include.NON_NULL) @Valid FunctionalTransferHistory.Definitions history,
                           @JsonInclude(JsonInclude.Include.NON_NULL) @Valid FunctionalTransferVersions.Definitions projectVersions) {
        public Snapshot(int version,List<Catalog> catalogs,List<Requirement> requirements,List<Case> cases,List<Plan> plans,FunctionalTransferGovernance.Definitions governance,FunctionalTransferDefects.Definitions defects,FunctionalTransferFiles.Definitions files,FunctionalTransferHistory.Definitions history) {
            this(version,catalogs,requirements,cases,plans,governance,defects,files,history,null);
        }
        public Snapshot(int version,List<Catalog> catalogs,List<Requirement> requirements,List<Case> cases,List<Plan> plans,FunctionalTransferGovernance.Definitions governance,FunctionalTransferDefects.Definitions defects,FunctionalTransferFiles.Definitions files) {
            this(version,catalogs,requirements,cases,plans,governance,defects,files,null);
        }
        public Snapshot(int version,List<Catalog> catalogs,List<Requirement> requirements,List<Case> cases,List<Plan> plans,FunctionalTransferGovernance.Definitions governance,FunctionalTransferDefects.Definitions defects) {
            this(version,catalogs,requirements,cases,plans,governance,defects,null);
        }
        public Snapshot(int version, List<Catalog> catalogs, List<Requirement> requirements, List<Case> cases, List<Plan> plans, FunctionalTransferGovernance.Definitions governance) {
            this(version, catalogs, requirements, cases, plans, governance, null);
        }
        // V1 must retain its exact serialized shape for existing receipt payload hashes.
        public Snapshot(int version, List<Catalog> catalogs, List<Requirement> requirements, List<Case> cases) {
            this(version, catalogs, requirements, cases, null);
        }
        public Snapshot(int version, List<Catalog> catalogs, List<Requirement> requirements, List<Case> cases, List<Plan> plans) {
            this(version, catalogs, requirements, cases, plans, null);
        }
    }
    public record Catalog(@NotBlank @Size(max=26) String id, @Size(max=26) String parentId, @NotBlank String scope,
                          @NotBlank @Size(max=160) String name, int sortOrder) {}
    public record Requirement(@NotBlank @Size(max=26) String id, @NotBlank @Size(max=26) String catalogId, @NotBlank @Size(max=40) String code,
                              @NotBlank @Size(max=200) String title, @NotBlank String priority, @NotBlank String status, @Size(max=20000) String description) {}
    public record Case(@NotBlank @Size(max=26) String id, @NotBlank @Size(max=26) String catalogId, @Size(max=26) String requirementId,
                       @NotBlank @Size(max=40) String code, @NotBlank @Size(max=200) String title, @NotBlank String priority,
                       @NotBlank String status, @NotBlank String caseType, @Size(max=20000) String precondition,
                       @NotBlank @Size(max=20000) String steps, @NotBlank @Size(max=20000) String expectedResult) {}
    public record ImportRequest(@NotBlank @Pattern(regexp="[a-f0-9-]{36}") String requestKey, @NotBlank @Size(max=26) String expectedUserId,
                                @NotNull @Valid Snapshot snapshot) {}
    public record Plan(@NotBlank @Size(max=26) String id, @Size(max=26) String catalogId, @NotBlank @Size(max=40) String code,
                       @NotBlank @Size(max=200) String name, @NotBlank String status, @Size(max=20000) String description,
                       @NotNull @Size(max=5000) List<@NotBlank @Size(max=26) String> caseIds) {}
    public record Receipt(String id, int catalogs, int requirements, int cases, Map<String, String> roots,
                          @JsonInclude(JsonInclude.Include.NON_NULL) Integer plans,
                          @JsonInclude(JsonInclude.Include.NON_NULL) FunctionalTransferGovernance.Counts governance,
                          @JsonInclude(JsonInclude.Include.NON_NULL) FunctionalTransferDefects.Counts defects,
                          @JsonInclude(JsonInclude.Include.NON_NULL) FunctionalTransferFiles.Counts files,
                          @JsonInclude(JsonInclude.Include.NON_NULL) FunctionalTransferHistory.Counts history,
                          @JsonInclude(JsonInclude.Include.NON_NULL) Integer projectVersions,
                          @JsonInclude(JsonInclude.Include.NON_NULL) Integer governanceCatalogs) {}
}
