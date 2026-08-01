package io.testhub.platform.version;

import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ProjectSummary;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
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
public class ProjectVersionService {
    private static final Set<String> WRITERS =
            Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private static final Set<String> STATUSES = Set.of("UNSTARTED", "RELEASED", "FAILED", "STOPPED");

    private final ProjectVersionRepository versions;
    private final AccessService access;
    private final AuditService audit;

    public ProjectVersionService(ProjectVersionRepository versions, AccessService access, AuditService audit) {
        this.versions = versions;
        this.access = access;
        this.audit = audit;
    }

    public List<ProjectVersion> list(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectRead(principal, projectId);
        return versions.findByProject(projectId);
    }

    @Transactional
    public ProjectVersion create(AuthenticatedPrincipal principal, String projectId, VersionInput input) {
        ProjectSummary project = writable(principal, projectId);
        ValidatedVersion version = validate(input, null);
        String id = UlidCreator.getUlid().toString();
        try {
            versions.insert(id, project.organizationId(), projectId, version.versionNumber(), version.buildNumbers(),
                    version.status(), version.plannedReleaseDate(), version.actualReleaseDate(),
                    version.description(), principal.userId());
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前项目已存在相同版本号", exception);
        }
        audit.record(principal, project.organizationId(), projectId, "PROJECT_VERSION_CREATED", "PROJECT_VERSION", id,
                Map.of("versionNumber", version.versionNumber(), "status", version.status()));
        return requireVersion(projectId, id);
    }

    @Transactional
    public ProjectVersion update(AuthenticatedPrincipal principal, String projectId, String versionId,
                                 VersionInput input) {
        ProjectSummary project = writable(principal, projectId);
        ProjectVersion existing = requireVersion(projectId, versionId);
        ValidatedVersion version = validate(input, existing);
        try {
            if (versions.update(projectId, versionId, version.versionNumber(), version.buildNumbers(),
                    version.status(), version.plannedReleaseDate(), version.actualReleaseDate(),
                    version.description(), principal.userId()) == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "版本不存在");
            }
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前项目已存在相同版本号", exception);
        }
        audit.record(principal, project.organizationId(), projectId, "PROJECT_VERSION_UPDATED", "PROJECT_VERSION",
                versionId, Map.of("versionNumber", version.versionNumber(), "status", version.status()));
        return requireVersion(projectId, versionId);
    }

    @Transactional
    public ProjectVersion updateStatus(AuthenticatedPrincipal principal, String projectId, String versionId,
                                       StatusInput input) {
        ProjectSummary project = writable(principal, projectId);
        ProjectVersion existing = requireVersion(projectId, versionId);
        String status = status(input == null ? null : input.status());
        LocalDate actualReleaseDate = actualReleaseDate(status,
                input == null ? null : input.actualReleaseDate(), existing.actualReleaseDate());
        if (versions.updateStatus(projectId, versionId, status, actualReleaseDate, principal.userId()) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "版本不存在");
        }
        audit.record(principal, project.organizationId(), projectId, "PROJECT_VERSION_STATUS_UPDATED",
                "PROJECT_VERSION", versionId, Map.of("from", existing.status(), "to", status));
        return requireVersion(projectId, versionId);
    }

    @Transactional
    public void delete(AuthenticatedPrincipal principal, String projectId, String versionId) {
        ProjectSummary project = writable(principal, projectId);
        ProjectVersion existing = requireVersion(projectId, versionId);
        if (versions.delete(projectId, versionId) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "版本不存在");
        }
        audit.record(principal, project.organizationId(), projectId, "PROJECT_VERSION_DELETED", "PROJECT_VERSION",
                versionId, Map.of("versionNumber", existing.versionNumber()));
    }

    private ValidatedVersion validate(VersionInput input, ProjectVersion existing) {
        if (input == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "版本参数不能为空");
        }
        String versionNumber = required(input.versionNumber(), 100, "版本号");
        List<String> buildNumbers = builds(input.buildNumbers());
        String status = input.status() == null || input.status().isBlank()
                ? existing == null ? "UNSTARTED" : existing.status()
                : status(input.status());
        LocalDate actualReleaseDate = actualReleaseDate(status, input.actualReleaseDate(),
                existing == null ? null : existing.actualReleaseDate());
        return new ValidatedVersion(versionNumber, buildNumbers, status, input.plannedReleaseDate(),
                actualReleaseDate, text(input.description(), 20_000, "版本描述"));
    }

    private List<String> builds(List<String> values) {
        if (values == null) {
            return List.of();
        }
        if (values.size() > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "包含构建不能超过50个");
        }
        return values.stream()
                .map(value -> {
                    String buildNumber = required(value, 100, "构建号");
                    if (buildNumber.contains("\n") || buildNumber.contains("\r")) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "构建号不能包含换行符");
                    }
                    return buildNumber;
                })
                .distinct()
                .toList();
    }

    private LocalDate actualReleaseDate(String status, LocalDate requested, LocalDate existing) {
        if ("RELEASED".equals(status)) {
            return requested != null ? requested : existing != null ? existing : LocalDate.now();
        }
        if ("STOPPED".equals(status)) {
            return requested != null ? requested : existing;
        }
        return null;
    }

    private String status(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "发布状态不合法");
        }
        return normalized;
    }

    private String required(String value, int max, String label) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "不能为空且不能超过" + max + "个字符");
        }
        return value.trim();
    }

    private String text(String value, int max, String label) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "不能超过" + max + "个字符");
        }
        return normalized;
    }

    private ProjectSummary writable(AuthenticatedPrincipal principal, String projectId) {
        return access.requireProjectParameterWrite(principal, projectId, WRITERS);
    }

    private ProjectVersion requireVersion(String projectId, String versionId) {
        return versions.findById(projectId, versionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "版本不存在"));
    }

    public record VersionInput(String versionNumber, List<String> buildNumbers, String status,
                               LocalDate plannedReleaseDate, LocalDate actualReleaseDate, String description) { }

    public record StatusInput(String status, LocalDate actualReleaseDate) { }

    private record ValidatedVersion(String versionNumber, List<String> buildNumbers, String status,
                                    LocalDate plannedReleaseDate, LocalDate actualReleaseDate,
                                    String description) { }
}
