package io.testhub.platform.execution;

import io.testhub.platform.access.AccessService;
import io.testhub.platform.apiautomation.ApiAutomationExecutionService;
import io.testhub.platform.apiautomation.ApiAutomationHistorySyncService;
import io.testhub.platform.functional.FunctionalTestExecutionService;
import io.testhub.platform.mobileautomation.MobileAutomationService;
import io.testhub.platform.performance.PerformanceTestExecutionService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.uiautomation.UiAutomationService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UnifiedExecutionService {
    private final AccessService access;
    private final ApiAutomationHistorySyncService apiHistory;
    private final FunctionalTestExecutionService functional;
    private final ApiAutomationExecutionService api;
    private final PerformanceTestExecutionService performance;
    private final UiAutomationService ui;
    private final MobileAutomationService mobile;

    public UnifiedExecutionService(AccessService access, FunctionalTestExecutionService functional,
            ApiAutomationExecutionService api, PerformanceTestExecutionService performance,
            UiAutomationService ui, MobileAutomationService mobile, ApiAutomationHistorySyncService apiHistory) {
        this.access = access;
        this.apiHistory = apiHistory;
        this.functional = functional;
        this.api = api;
        this.performance = performance;
        this.ui = ui;
        this.mobile = mobile;
    }

    @Transactional(readOnly = true)
    public List<UnifiedExecution> list(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectRead(principal, projectId);
        List<UnifiedExecution> result = new ArrayList<>();
        functional.recentRuns(principal, projectId).forEach(r -> result.add(new UnifiedExecution(
                r.id(), r.projectId(), "FUNCTIONAL", r.name(), r.status(), r.createdAt(), r.startedAt(),
                r.finishedAt(), r.updatedAt(), r.totalCount(), r.passedCount(), r.failedCount(), r.blockedCount())));
        api.list(principal, projectId).forEach(r -> result.add(new UnifiedExecution(
                r.id(), r.projectId(), "API", r.scenarioName(), r.status(), r.createdAt(), r.startedAt(),
                r.finishedAt(), r.updatedAt(), null, null, null, null)));
        apiHistory.recentArchives(principal, projectId).forEach(r -> result.add(new UnifiedExecution(
                r.key(), projectId, "API_HISTORY", r.scenarioName(), r.status(), r.requestedAt(), r.startedAt(),
                r.finishedAt(), r.updatedAt(), null, null, null, null)));
        performance.list(principal, projectId).forEach(r -> result.add(new UnifiedExecution(
                r.id(), r.projectId(), "PERFORMANCE", r.planName(), r.status(), r.createdAt(), r.startedAt(),
                r.finishedAt(), r.updatedAt(), null, null, null, null)));
        ui.executions(principal, projectId).forEach(r -> result.add(new UnifiedExecution(
                r.id(), r.projectId(), "UI", r.scenarioName(), r.status(), r.createdAt(), r.startedAt(),
                r.finishedAt(), r.updatedAt(), null, null, null, null)));
        mobile.executions(principal, projectId).forEach(r -> result.add(new UnifiedExecution(
                r.id(), r.projectId(), "MOBILE", r.scenarioName(), r.status(), r.createdAt(), r.startedAt(),
                r.finishedAt(), r.updatedAt(), null, null, null, null)));
        result.sort(Comparator.comparing(UnifiedExecution::createdAt).reversed()
                .thenComparing(UnifiedExecution::module).thenComparing(UnifiedExecution::id, Comparator.reverseOrder()));
        return List.copyOf(result);
    }
}
