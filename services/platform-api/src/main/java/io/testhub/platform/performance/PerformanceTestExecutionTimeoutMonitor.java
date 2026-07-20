package io.testhub.platform.performance;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PerformanceTestExecutionTimeoutMonitor {
    private final PerformanceTestExecutionService executions;

    public PerformanceTestExecutionTimeoutMonitor(PerformanceTestExecutionService executions) {
        this.executions = executions;
    }

    @Scheduled(fixedDelayString = "${testhub.performance-runner.executions.timeout-sweep-interval-ms:1000}")
    public void expireDueExecutions() {
        executions.expireDueExecutions();
    }

    @Scheduled(fixedDelayString = "${testhub.performance-runner.executions.runner-offline-sweep-interval-ms:5000}")
    public void expireOfflineRunnerExecutions() {
        executions.expireOfflineRunnerExecutions();
    }
}
