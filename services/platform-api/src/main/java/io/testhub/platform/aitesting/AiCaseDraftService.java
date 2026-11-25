package io.testhub.platform.aitesting;

import io.testhub.platform.functional.FunctionalAssetService;
import io.testhub.platform.functional.FunctionalAssetService.CaseInput;
import io.testhub.platform.functional.FunctionalCase;
import io.testhub.platform.security.AuthenticatedPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class AiCaseDraftService {
    private final AiAgentService agents;
    private final FunctionalAssetService assets;

    AiCaseDraftService(AiAgentService agents, FunctionalAssetService assets) {
        this.agents = agents;
        this.assets = assets;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<FunctionalCase> save(AuthenticatedPrincipal actor, String projectId, DraftImport request) {
        agents.writeScope(actor, projectId);
        String prefix = "AI-" + request.requestId().toString().replace("-", "").toUpperCase(Locale.ROOT) + "-";
        Map<String, FunctionalCase> previous = new HashMap<>();
        assets.listCases(actor, projectId, request.catalogId()).forEach(row -> {
            if (row.caseCode().startsWith(prefix)) previous.put(row.caseCode(), row);
        });
        if (!previous.isEmpty() && previous.size() != request.cases().size()) throw reused();
        List<FunctionalCase> result = new ArrayList<>();
        for (int index = 0; index < request.cases().size(); index++) {
            var item = request.cases().get(index);
            String code = prefix + (index + 1);
            var found = previous.get(code);
            if (!previous.isEmpty()) {
                if (found == null || !sameDraft(found, request.requirementId(), item)) throw reused();
                result.add(found);
            } else {
                result.add(assets.createCase(actor, projectId, new CaseInput(request.catalogId(), request.requirementId(),
                        code, item.title(), item.priority(), "DRAFT", "FUNCTIONAL",
                        item.precondition(), item.steps(), item.expectedResult())));
            }
        }
        return List.copyOf(result);
    }

    private boolean sameDraft(FunctionalCase row, String requirementId, CaseDraft draft) {
        return Objects.equals(row.requirementId(), requirementId) && row.title().equals(draft.title().trim())
                && row.priority().equals(draft.priority()) && row.precondition().equals(text(draft.precondition()))
                && row.steps().equals(draft.steps().trim()) && row.expectedResult().equals(draft.expectedResult().trim());
    }

    private static String text(String value) { return value == null ? "" : value.trim(); }

    private static ResponseStatusException reused() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "该批草稿已写入过，请在用例库检查记录");
    }

    public record DraftImport(@NotNull UUID requestId, @NotBlank @Size(max = 26) String catalogId,
                              @Size(max = 26) String requirementId,
                              @NotNull @Size(min = 1, max = 10) List<@NotNull @Valid CaseDraft> cases) { }

    public record CaseDraft(@NotBlank @Size(max = 200) String title, @NotNull @Pattern(regexp = "P[0-3]") String priority,
                            @Size(max = 20000) String precondition, @NotBlank @Size(max = 20000) String steps,
                            @NotBlank @Size(max = 20000) String expectedResult) { }
}
