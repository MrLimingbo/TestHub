package io.testhub.platform.performance;

import io.testhub.platform.audit.AuditService;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PerformanceRunnerRuntimeConfigurationService {
    private static final Set<String> ELIGIBLE_STATUSES = Set.of("QUEUED", "ACCEPTED");
    private static final Pattern TEMPLATE = Pattern.compile("\\$\\{[A-Za-z_][A-Za-z0-9_]{0,63}}");
    private static final String TEMPLATE_URL_SENTINEL = "__testhub_data_variable__";
    private final PerformanceTestExecutionRepository executions;
    private final AuditService audit;

    public PerformanceRunnerRuntimeConfigurationService(PerformanceTestExecutionRepository executions, AuditService audit) {
        this.executions = executions;
        this.audit = audit;
    }

    @Transactional
    public PerformanceRunnerRuntimeConfiguration issue(String runnerId, String taskId, String executionId) {
        PerformanceTestExecution execution = executions.findByTask(taskId, executionId)
                .orElseThrow(this::rejected);
        if (!ELIGIBLE_STATUSES.contains(execution.status())
                || (execution.runnerId() != null && !runnerId.equals(execution.runnerId()))) throw rejected();
        executions.claimRuntimeExecution(taskId, executionId, runnerId);
        // Re-read after the conditional claim. A task can reach a terminal state between the
        // first read and this update, so the pre-claim snapshot cannot authorize an artifact.
        execution = executions.findByTask(taskId, executionId).orElseThrow(this::rejected);
        if (!"ACCEPTED".equals(execution.status()) || !runnerId.equals(execution.runnerId())) throw rejected();
        PerformanceTestExecutionManifest manifest = executions.findManifest(execution.projectId(), execution.id())
                .orElseThrow(this::rejected);
        List<PerformanceRunnerRuntimeConfiguration.ThreadGroup> groups = manifest.snapshot().threadGroups().stream()
                .map(group -> new PerformanceRunnerRuntimeConfiguration.ThreadGroup(group.name(), validTarget(group.targetUrl()),
                        group.loadModel(), group.threadCount(), group.rampUpSeconds(), group.stepCount(), group.stepIntervalSeconds(), group.durationSeconds(), group.targetTps(),
                        group.requestMethod(), group.requestHeaders(), group.queryParameters(), group.requestBody(), group.expectedStatusCode(),
                        group.responseContains(), group.dataSetCsv(), group.rendezvousUsers()))
                .toList();
        audit.recordSystem(execution.organizationId(), execution.projectId(), "PERFORMANCE_RUNTIME_CONFIGURATION_ISSUED",
                "PERFORMANCE_TEST_EXECUTION", execution.id(), Map.of("runnerId", runnerId, "taskId", taskId,
                        "threadGroupCount", groups.size(), "artifactVersion", 6));
        return new PerformanceRunnerRuntimeConfiguration("testhub.jmeter-runner.runtime-configuration", 6,
                execution.id(), execution.taskId(), execution.deadlineAt(), groups);
    }

    private String validTarget(String value) {
        try {
            URI target = URI.create(TEMPLATE.matcher(value).replaceAll(TEMPLATE_URL_SENTINEL));
            if (!target.isAbsolute() || target.getHost() == null || target.getUserInfo() != null
                    || !("http".equalsIgnoreCase(target.getScheme()) || "https".equalsIgnoreCase(target.getScheme()))) {
                throw rejected();
            }
            if (target.getRawAuthority() != null && target.getRawAuthority().contains(TEMPLATE_URL_SENTINEL)) throw rejected();
            return value;
        } catch (IllegalArgumentException exception) {
            throw rejected();
        }
    }

    private ResponseStatusException rejected() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "JMeter Runner 运行时工件不可用");
    }
}
