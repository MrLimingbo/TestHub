package io.testhub.platform.functional;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.version.ProjectVersionService;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;
import java.util.function.UnaryOperator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Project release definitions shared by the functional version-management page. */
@Service
public class FunctionalTransferVersions {
    private final ProjectVersionService versions;
    private final Validator validator;
    public FunctionalTransferVersions(ProjectVersionService versions, Validator validator) {
        this.versions = versions;
        this.validator = validator;
    }

    public Definitions export(AuthenticatedPrincipal principal, String project) {
        return new Definitions(versions.list(principal, project).stream().map(item -> new Version(item.id(),
                item.versionNumber(), item.buildNumbers(), item.status(), item.plannedReleaseDate(),
                item.actualReleaseDate(), item.description())).toList());
    }

    public void validate(Definitions data, Set<String> ids) {
        if (data == null || !validator.validate(data).isEmpty()) throw invalid("项目版本格式无效或超过 5000 条。");
        Set<String> numbers = new HashSet<>();
        for (var item : data.items()) {
            if (!ids.add(item.id()) || !numbers.add(key(item.number()))) throw invalid("项目版本标识或版本号重复。");
            var builds = item.builds().stream().map(String::trim).toList();
            if (new HashSet<>(builds).size() != builds.size() || builds.stream().anyMatch(value -> value.contains("\n") || value.contains("\r")))
                throw invalid("项目版本的构建号重复或含换行符。");
            if ((item.status().equals("RELEASED") && item.actualReleaseDate() == null)
                    || (Set.of("UNSTARTED", "FAILED").contains(item.status()) && item.actualReleaseDate() != null))
                throw invalid("版本状态与实际发布日期不一致。");
            for (var date : Arrays.asList(item.plannedReleaseDate(), item.actualReleaseDate())) {
                if (date != null && (date.getYear() < 1000 || date.getYear() > 9999)) throw invalid("版本日期超出数据库支持范围。");
            }
        }
    }

    public void checkConflicts(AuthenticatedPrincipal principal, String project, Definitions data) {
        Set<String> numbers = new HashSet<>();
        versions.list(principal, project).forEach(item -> numbers.add(key(item.versionNumber())));
        for (var item : data.items()) if (numbers.contains(key(item.number())))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "目标项目已有版本号：" + item.number());
    }

    public int transfer(AuthenticatedPrincipal principal, String project, Definitions data) {
        for (var item : data.items()) versions.create(principal, project, new ProjectVersionService.VersionInput(
                item.number(), item.builds(), item.status(), item.plannedReleaseDate(), item.actualReleaseDate(), item.description()));
        return data.items().size();
    }

    public static Definitions rewrite(Definitions data, UnaryOperator<String> text) {
        return data == null ? null : new Definitions(data.items().stream().map(item -> new Version(item.id(), item.number(),
                item.builds(), item.status(), item.plannedReleaseDate(), item.actualReleaseDate(), text.apply(item.description()))).toList());
    }

    private static String key(String value) { return value.trim().toUpperCase(Locale.ROOT); }
    private static ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    public record Definitions(@NotNull @Size(max=5000) List<@NotNull @Valid Version> items) {}
    public record Version(@NotBlank @Size(max=26) String id, @NotBlank @Size(max=100) String number,
                          @NotNull @Size(max=50) List<@NotBlank @Size(max=100) String> builds,
                          @NotNull @Pattern(regexp="UNSTARTED|RELEASED|FAILED|STOPPED") String status,
                          LocalDate plannedReleaseDate, LocalDate actualReleaseDate, @NotNull @Size(max=20000) String description) {}
}
