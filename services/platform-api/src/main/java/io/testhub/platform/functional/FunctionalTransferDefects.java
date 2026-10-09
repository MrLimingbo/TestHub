package io.testhub.platform.functional;

import io.testhub.platform.security.AuthenticatedPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Portable defect definitions and links to the requirements/cases copied in the same transaction. */
@Service
public class FunctionalTransferDefects {
    private final FunctionalDefectService defects;
    public FunctionalTransferDefects(FunctionalDefectService defects) { this.defects = defects; }

    public Definitions export(AuthenticatedPrincipal principal, String project) {
        return export(principal, project, false);
    }
    public Definitions export(AuthenticatedPrincipal principal, String project, boolean includeHistory) {
        return new Definitions(defects.list(principal, project).stream().map(item -> {
            var links = defects.links(principal, project, item.id());
            return new Defect(item.id(), item.catalogId(), item.defectCode(), item.title(), item.dueDate(), item.bugType(),
                    item.keywords(), item.browsers(), item.operatingSystems(), item.severity(), item.priority(), item.status(),
                    item.confirmed(), item.resolution(), item.source(), item.externalUrl(), item.description(),
                    links.stream().filter(link -> includeHistory || !"RUN_CASE".equals(link.assetType())).map(link -> new Link(link.assetType(), link.assetId())).toList(),
                    includeHistory ? 0 : (int) links.stream().filter(link -> "RUN_CASE".equals(link.assetType())).count());
        }).toList());
    }

    public void validate(Definitions data, FunctionalTransferService.Snapshot snapshot, Set<String> ids) {
        if (data == null || data.items() == null || data.items().size() > 5000) throw invalid("缺陷资料不完整或超过 5000 条。");
        var catalogs = new HashSet<String>();
        snapshot.catalogs().stream().filter(item -> "DELIVERY".equals(item.scope())).forEach(item -> catalogs.add(item.id()));
        Map<String, Set<String>> assets = new HashMap<>(Map.of("REQUIREMENT", new HashSet<>(snapshot.requirements().stream().map(FunctionalTransferService.Requirement::id).toList()),
                "CASE", new HashSet<>(snapshot.cases().stream().map(FunctionalTransferService.Case::id).toList())));
        if (snapshot.history() != null) assets.put("RUN_CASE", new HashSet<>(snapshot.history().runs().stream().flatMap(run -> run.cases().stream()).map(FunctionalTransferHistory.RunCase::id).toList()));
        Set<String> codes = new HashSet<>(); long links = 0, omitted = 0;
        for (var item : data.items()) {
            if (item == null || item.id() == null || !ids.add(item.id()) || item.code() == null || !codes.add(item.code().trim().toUpperCase(Locale.ROOT)))
                throw invalid("缺陷标识或编号重复。");
            if (item.catalogId() != null && !catalogs.contains(item.catalogId())) throw invalid("缺陷目录不在本次迁移范围内。");
            if (item.links() == null || item.links().size() > 5000 || item.omittedExecutionLinks() < 0) throw invalid("缺陷关联数量无效。");
            Set<Link> unique = new HashSet<>();
            for (var link : item.links()) {
                if (link == null || link.assetType() == null || !assets.containsKey(link.assetType()) || !assets.get(link.assetType()).contains(link.assetId()) || !unique.add(link))
                    throw invalid("缺陷关联重复或不属于本次迁移的需求、用例或执行记录。");
            }
            links += item.links().size(); omitted += item.omittedExecutionLinks();
            if (links > 50000 || omitted > 50000) throw invalid("单次最多处理 50000 条缺陷关联或未迁入的执行关联。");
        }
    }
    public void checkConflicts(AuthenticatedPrincipal principal, String project, Definitions data) {
        Set<String> codes = new HashSet<>();
        defects.list(principal, project).forEach(item -> codes.add(item.defectCode().toUpperCase(Locale.ROOT)));
        for (var item : data.items()) if (codes.contains(item.code().trim().toUpperCase(Locale.ROOT)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "目标项目已有缺陷编号：" + item.code());
    }
    public Counts transfer(AuthenticatedPrincipal principal, String project, Definitions data, Map<String,String> catalogs, Map<String,String> assets) {
        for (var item : data.items()) {
            var created = defects.createTransferred(principal, project, new FunctionalDefectService.DefectInput(
                    item.catalogId() == null ? null : catalogs.get(item.catalogId()), item.code(), item.title(), item.dueDate(), item.bugType(), item.keywords(),
                    item.browsers(), item.operatingSystems(), List.of(), item.severity(), item.priority(), item.status(), item.confirmed(), null,
                    item.resolution(), item.source(), item.externalUrl(), item.description()));
            for (var link : item.links()) defects.link(principal, project, created.id(), new FunctionalDefectService.DefectLinkInput(link.assetType(), assets.get(link.assetId())));
        }
        return new Counts(data.items().size(), data.items().stream().mapToInt(item -> item.links().size()).sum(), data.items().stream().mapToInt(Defect::omittedExecutionLinks).sum());
    }
    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }

    public record Definitions(@NotNull @Size(max=5000) List<@NotNull @Valid Defect> items) {}
    public record Link(@NotBlank @Size(max=24) String assetType, @NotBlank @Size(max=26) String assetId) {}
    public record Defect(@NotBlank @Size(max=26) String id, @Size(max=26) String catalogId, @NotBlank @Size(max=40) String code,
                         @NotBlank @Size(max=200) String title, LocalDate dueDate, @NotBlank @Size(max=32) String bugType,
                         @Size(max=500) String keywords, @NotNull @Size(max=7) List<@NotBlank @Size(max=24) String> browsers,
                         @NotNull @Size(max=5) List<@NotBlank @Size(max=24) String> operatingSystems,
                         @NotBlank String severity, @NotBlank String priority, @NotBlank String status, boolean confirmed,
                         @Size(max=24) String resolution, @NotBlank @Size(max=24) String source, @Size(max=1000) String externalUrl,
                         @NotBlank @Size(max=20000) String description, @NotNull @Size(max=5000) List<@NotNull @Valid Link> links,
                         @Min(0) @Max(50000) int omittedExecutionLinks) {}
    public record Counts(int items, int links, int omittedExecutionLinks) {}
}
